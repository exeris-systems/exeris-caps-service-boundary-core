package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link ApiSurfaceRegistry} and {@link RequestFilterChain}.
 *
 * <p>Thread-safe route registry, filter chain, and dispatcher for Service Boundary SKUs.
 *
 * <p>Each matched request gets one {@link DefaultAdmissionContext}. The filters run with it and with the
 * {@link RequestContext} established so far bound; the handler runs only after every filter passed, inside
 * the sealed {@link RequestContext}, so the principal a handler reads is the one admission established.
 *
 * <p>After the filters, the route's {@link RouteRequirement}s are enforced against the admitted principal
 * before the handler runs; a request that does not satisfy them is answered 401 or 403.
 *
 * <p>No matched exchange is left unanswered. A filter that returns {@code false} without having answered, and a
 * handler that returns without having answered, are answered 500 by the registry; a filter or handler that
 * throws an {@link Exception} is answered 500 when still unanswered; one that throws an {@link Error} is
 * answered 500 when still unanswered and the error is rethrown (a {@link VirtualMachineError} is rethrown
 * without an attempt to answer).
 *
 * <p>An exchange is answered when a {@code respond} call on it returned normally. A {@code respond} call that
 * threw has not answered it, and the registry answers 500 once, whether the kernel had claimed the response
 * before it failed (the attempt is then refused) or not (the attempt then succeeds); it cannot tell which
 * beforehand. An exchange that has been answered is never answered again by the registry.
 *
 * <p>Once an exchange is answered, admission is over. After each filter returns, the registry reads whether a
 * {@code respond} was attempted on the exchange; if so, the remaining filters and the handler do not run,
 * whatever the filter returned, because a filter that tried to answer has decided to refuse the request. If the
 * attempt did not complete the registry answers 500. A filter that answered and returned {@code true} is a
 * contract violation and is logged once at WARNING. A second {@code respond} issued by a filter or handler is
 * refused by the kernel's exchange with {@link IllegalStateException}; the registry adds no guard of its own.
 *
 * <p>Every answer the registry writes itself (401, 403, 404, 405, 503 and 500) goes through one rule. If the
 * write fails and the failure being handled is an {@link Error}, that error leaves the registry with the write
 * failure attached as suppressed. Otherwise the write failure is logged once at ERROR, naming the status, what
 * was being answered, the route's path pattern when a route matched and the failure's class, and the registry
 * returns normally, since nothing more can be done for the exchange. A request whose denial could not be written
 * is finished: the handler does not run. A {@link VirtualMachineError} thrown by a write always propagates.
 *
 * <p>The registry moves one way through three phases: {@code OPEN}, {@code FROZEN}, {@code TEARDOWN}
 * ({@code OPEN} may go straight to {@code TEARDOWN}). While {@code OPEN} routes and filters may be added and
 * removed. While {@code FROZEN} the service is serving and nothing may be added, replaced or removed. In
 * {@code TEARDOWN} serving has ended: every request is answered 503, removal is permitted so that capabilities
 * can detach, and addition stays refused. Every mutator and every phase transition runs under one lock, so a
 * mutation either happens before a transition or observes it; the request path never takes that lock.
 *
 * <p><b>Invariant: the ordered filters are read once per request, before the phase.</b> The filters a request
 * runs are one immutable array, already sorted by phase and then name, held in the volatile field
 * {@code orderedFilters}. Every mutator ({@code registerFilter}, {@code unregisterFilter}, {@code clear}) builds a
 * new array and replaces the field with one volatile write, still inside {@code mutationLock}; no array is
 * ever modified after it is published. A request reads the field once, and only afterwards reads {@code phase};
 * it never reads the field again, so the whole request runs against one array.
 *
 * <p>Removal is permitted while {@code OPEN}, before serving begins, and in {@code TEARDOWN}, after it ended;
 * never while {@code FROZEN}. A removal in {@code TEARDOWN} runs only after the mutator, holding the lock,
 * observed {@code phase} at {@code TEARDOWN}, a value written under the same lock, so its write of
 * {@code orderedFilters} follows the {@code TEARDOWN} write in the synchronisation order. Suppose a request's
 * read of {@code orderedFilters} returned an array written by such a removal. That read synchronises with the
 * write, so the {@code TEARDOWN} write happens before the request's later read of {@code phase}, and since no
 * write follows {@code TEARDOWN} that read returns {@code TEARDOWN}. Read the other way round: a request that
 * does not observe {@code TEARDOWN} read an array that predates every removal made during {@code TEARDOWN}, and
 * runs every filter that was registered when serving ended. A request that does observe {@code TEARDOWN}
 * answers 503 and runs none, so whichever array it holds is not used. No request can therefore pair an array
 * with a phase that contradicts it. Additions are refused once the phase left {@code OPEN}, so while
 * {@code FROZEN} the field holds one array. An array is complete when read, because its elements are written
 * before the volatile write that publishes it.
 *
 * <p>The sort key is the phase and name a filter reported when it was registered. Both are read exactly once,
 * inside {@code registerFilter} before anything is changed, and kept in an immutable {@code RegisteredFilter};
 * sorting, the duplicate-name check and the record written for the registration use those captured values; the
 * other log records name the filter's class. {@code name()} and {@code phase()} are never called on a
 * registered filter again, so a filter that later
 * reports another name or phase keeps its place and its name. The order is not recomputed per request, and a
 * rebuild sorts the captured values, not the filters. {@code registerFilter} validates everything and builds the
 * new array before it changes the list or replaces {@code orderedFilters}, so a registration that throws, at any
 * point, leaves both as they were. {@link #filters()} returns a fresh unmodifiable list of that order and
 * exposes no live state.
 *
 * <p><b>A change that has been made is not undone by a failure to log it.</b> Every mutator
 * ({@code registerFilter}, {@code unregisterFilter}, {@code registerRoute}, {@code unregisterRoute},
 * {@code clear}, {@code freeze}, {@code beginTeardown}) either throws and leaves the registry as it was, or
 * commits and returns normally. A log record is written after the lock is released and after the commit; a
 * failure to write it is diagnostics lost and is swallowed (a {@link VirtualMachineError} is not), and nothing
 * else runs between the commit and the return. No code supplied by a caller runs under {@code mutationLock}: a
 * filter is identified by reference ({@code ==}), so {@code equals}, {@code hashCode} and {@code toString} of a
 * filter are never called by the registry, and a filter is removed by passing the same instance that was
 * registered. A route is removed when it equals the given one, which compares its method, path pattern and
 * action name (library types only), and its handler is the same instance.
 *
 * @since 0.1.0
 */
public final class DefaultApiSurfaceRegistry implements ApiSurfaceRegistry, RequestFilterChain {

    private static final System.Logger LOG = System.getLogger(DefaultApiSurfaceRegistry.class.getName());

    private static final Pattern PARAM_SEGMENT = Pattern.compile("\\{[a-zA-Z0-9_]+\\}");

    private static final AdmissionFilter[] NO_FILTERS = new AdmissionFilter[0];

    private static final Comparator<RegisteredFilter> FILTER_COMPARATOR = Comparator
            .comparing(RegisteredFilter::phase)
            .thenComparing(RegisteredFilter::name);

    /** A filter with the name and phase it reported when it was registered; immutable, so it never drifts. */
    private record RegisteredFilter(AdmissionFilter filter, String name, AdmissionPhase phase) {
    }

    /**
     * A registered route together with the match it always produces, when it has one: a route without path
     * parameters matches every request with the same route and no parameters, so that match is built once at
     * registration. A {@link RouteMatch} is an immutable record of an immutable route and an immutable, empty
     * parameter map, holds no per-request state and is compared by value, so sharing one instance between
     * requests and threads is indistinguishable from building one per request.
     */
    private record RegisteredRoute(ApiSurfaceRoute route, RouteMatch literalMatch) {
        static RegisteredRoute of(ApiSurfaceRoute route) {
            return new RegisteredRoute(route, route.paramNames().isEmpty() ? new RouteMatch(route, Map.of()) : null);
        }
    }

    private final List<RegisteredRoute> routeList = new CopyOnWriteArrayList<>();
    /** The registered filters in registration order; read and written only while holding {@code mutationLock}. */
    private final List<RegisteredFilter> filterList = new ArrayList<>();
    /** The filters sorted by phase and then name; replaced as a whole under {@code mutationLock}, never modified. */
    private volatile AdmissionFilter[] orderedFilters = NO_FILTERS;
    private volatile boolean strictRoutes;
    private final Object mutationLock = new Object();
    private volatile Phase phase = Phase.OPEN;

    private enum Phase { OPEN, FROZEN, TEARDOWN }

    /** Creates a strict registry: a route that declares no requirement is refused at registration. */
    public DefaultApiSurfaceRegistry() {
        this(true);
    }

    /**
     * Creates a registry with the given route policy.
     *
     * @param strictRoutes {@code true} to refuse a route that declares no requirement at registration;
     *                     {@code false} to accept it and serve it to a request that no filter authenticated
     */
    public DefaultApiSurfaceRegistry(boolean strictRoutes) {
        this.strictRoutes = strictRoutes;
    }

    /**
     * Ends the open phase: from now on nothing can be added, replaced or removed until teardown. Idempotent.
     *
     * @throws IllegalStateException when the registry is already being torn down
     */
    public void freeze() {
        synchronized (mutationLock) {
            if (phase == Phase.TEARDOWN) {
                throw new IllegalStateException("The registry is being torn down and cannot be made ready");
            }
            phase = Phase.FROZEN;
        }
    }

    /** Ends serving: requests are refused, removal is permitted, addition stays refused. One-way, idempotent. */
    public void beginTeardown() {
        synchronized (mutationLock) {
            phase = Phase.TEARDOWN;
        }
    }

    /** Whether the registry admits no addition (frozen or being torn down). */
    public boolean isFrozen() {
        return phase != Phase.OPEN;
    }

    /** Whether serving has ended and removal is permitted. */
    public boolean isTearingDown() {
        return phase == Phase.TEARDOWN;
    }

    public boolean isStrictRoutes() {
        return strictRoutes;
    }

    /** Must be called holding {@code mutationLock}. */
    private void checkAdditionAllowed() {
        switch (phase) {
            case OPEN -> { }
            case FROZEN -> throw new IllegalStateException("The registry is frozen and can no longer be modified");
            case TEARDOWN -> throw new IllegalStateException("The registry is being torn down and admits no addition");
        }
    }

    /** Must be called holding {@code mutationLock}. */
    private void checkRemovalAllowed() {
        if (phase == Phase.FROZEN) {
            throw new IllegalStateException("The registry is frozen and can no longer be modified");
        }
    }

    @Override
    public void registerRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        synchronized (mutationLock) {
            checkAdditionAllowed();
            if (strictRoutes && route.requirements().isEmpty()) {
                throw new IllegalStateException("Strict route mode: route " + route.method() + " "
                        + route.pathPattern() + " declared no requirements");
            }
            for (RegisteredRoute entry : routeList) {
                ApiSurfaceRoute existing = entry.route();
                if (existing.method() == route.method()
                        && shape(existing.pathPattern()).equals(shape(route.pathPattern()))) {
                    throw new IllegalStateException("A route is already registered for " + route.method()
                            + " " + route.pathPattern());
                }
            }
            routeList.add(RegisteredRoute.of(route));
        }
        diagnostic(System.Logger.Level.DEBUG, "Registered boundary route: {0}", route);
    }

    /**
     * Writes a record after a mutation has been committed. The record is diagnostics: a handler or formatter
     * that fails must not make a call throw when the registry has already changed, so the failure is swallowed.
     * A {@link VirtualMachineError} is not.
     */
    private static void diagnostic(System.Logger.Level level, String format, Object... arguments) {
        try {
            LOG.log(level, format, arguments);
        } catch (VirtualMachineError err) {
            throw err;
        } catch (Throwable ignored) {
            // diagnostics only; the registry has already changed
        }
    }

    @Override
    public boolean unregisterRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        synchronized (mutationLock) {
            checkRemovalAllowed();
            return routeList.removeIf(entry -> entry.route().equals(route)
                    && entry.route().handler() == route.handler());
        }
    }

    @Override
    public void registerFilter(AdmissionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        String name = filter.name();
        AdmissionPhase filterPhase = filter.phase();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An admission filter needs a non-blank name");
        }
        if (filterPhase == null) {
            throw new IllegalArgumentException("Admission filter " + name + " needs a phase");
        }
        RegisteredFilter registered = new RegisteredFilter(filter, name, filterPhase);
        synchronized (mutationLock) {
            checkAdditionAllowed();
            for (RegisteredFilter existing : filterList) {
                if (existing.filter() == filter) {
                    throw new IllegalStateException("Admission filter " + name + " is already registered");
                }
                if (name.equals(existing.name())) {
                    throw new IllegalStateException("An admission filter named " + name + " is already registered");
                }
            }
            RegisteredFilter[] candidates = filterList.toArray(new RegisteredFilter[filterList.size() + 1]);
            candidates[candidates.length - 1] = registered;
            AdmissionFilter[] sorted = sortedFilters(candidates);
            filterList.add(registered);
            orderedFilters = sorted;
        }
        diagnostic(System.Logger.Level.DEBUG, "Registered admission filter: {0} ({1})", name, filterPhase);
    }

    @Override
    public boolean unregisterFilter(AdmissionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        synchronized (mutationLock) {
            checkRemovalAllowed();
            for (int i = 0; i < filterList.size(); i++) {
                if (filterList.get(i).filter() == filter) {
                    RegisteredFilter[] remaining = new RegisteredFilter[filterList.size() - 1];
                    for (int k = 0, j = 0; k < filterList.size(); k++) {
                        if (k != i) {
                            remaining[j++] = filterList.get(k);
                        }
                    }
                    orderedFilters = sortedFilters(remaining);
                    filterList.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    /** Sorts the captured name and phase, never the live filters, and returns the filters in that order. */
    private static AdmissionFilter[] sortedFilters(RegisteredFilter[] registered) {
        Arrays.sort(registered, FILTER_COMPARATOR);
        AdmissionFilter[] sorted = new AdmissionFilter[registered.length];
        for (int i = 0; i < registered.length; i++) {
            sorted[i] = registered[i].filter();
        }
        return sorted;
    }

    @Override
    public List<AdmissionFilter> filters() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(orderedFilters)));
    }

    @Override
    public RequestFilterChain filterChain() {
        return this;
    }

    @Override
    public boolean execute(HttpExchange exchange, RouteMatch routeMatch) {
        DefaultAdmissionContext admission = DefaultAdmissionContext.isBound()
                ? DefaultAdmissionContext.currentInternal()
                : DefaultAdmissionContext.open();
        return admit(exchange, routeMatch, admission);
    }

    private boolean admit(HttpExchange exchange, RouteMatch routeMatch, DefaultAdmissionContext admission) {
        MatchedHttpExchange tracked = exchange instanceof MatchedHttpExchange matched
                ? matched
                : new MatchedHttpExchange(exchange, exchange.pathParams());
        // The array is read before the phase and not again: a removal is permitted only after teardown was
        // written, so a request that does not see teardown holds an array that predates every removal.
        AdmissionFilter[] ordered = orderedFilters;
        if (phase == Phase.TEARDOWN) {
            if (!tracked.responded()) {
                answer(tracked, HttpStatus.SERVICE_UNAVAILABLE, null, "registry is tearing down", routeMatch, null);
            }
            return false;
        }
        for (int i = 0; i < ordered.length; i++) {
            AdmissionFilter filter = ordered[i];
            try {
                boolean proceed = DefaultAdmissionContext.callWith(admission,
                        () -> DefaultRequestContext.bind(admission.requestContext(),
                                () -> filter.filter(tracked, routeMatch)));
                if (tracked.attempted()) {
                    if (!tracked.responded()) {
                        // The filter tried to answer and the answer did not complete. It has decided to refuse,
                        // so admission ends here whatever it returned.
                        LOG.log(System.Logger.Level.WARNING,
                                "Admission filter {0} tried to answer route {1} and the answer did not complete; "
                                        + "answering 500",
                                filter.getClass().getName(), routeMatch.route().pathPattern());
                        answerInternalError(tracked, null, routeMatch);
                    } else if (proceed) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Admission filter {0} answered route {1} and returned true; admission ends",
                                filter.getClass().getName(), routeMatch.route().pathPattern());
                    } else {
                        LOG.log(System.Logger.Level.DEBUG, "Filter {0} halted admission pipeline", filter.getClass().getName());
                    }
                    return false;
                }
                if (!proceed) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Admission filter {0} halted route {1} without answering; answering 500",
                            filter.getClass().getName(), routeMatch.route().pathPattern());
                    answerInternalError(tracked, null, routeMatch);
                    return false;
                }
            } catch (TenantConflictException ex) {
                LOG.log(System.Logger.Level.DEBUG, "Tenant conflict in admission filter {0}", filter.getClass().getName());
                if (!tracked.responded()) {
                    answer(tracked, HttpStatus.FORBIDDEN, null, "tenant conflict", routeMatch, null);
                }
                return false;
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Admission filter {0} threw {1} on route {2}",
                        filter.getClass().getName(), ex.getClass().getName(), routeMatch.route().pathPattern());
                answerInternalError(tracked, ex, routeMatch);
                return false;
            } catch (Error err) {
                failOnError(err, tracked, "admission filter", filter.getClass().getName(), routeMatch);
                throw err;
            }
        }
        return true;
    }

    /**
     * Answers 500 for an exchange that is still unanswered when a filter or handler threw an {@link Error},
     * after which the caller rethrows that same error. A {@link VirtualMachineError} is not answered: the
     * JVM state is unreliable, answering could fail or hide the error, and the error is the signal that
     * matters.
     */
    private static void failOnError(Error err, MatchedHttpExchange exchange, String stage, String componentClass,
                                    RouteMatch routeMatch) {
        if (err instanceof VirtualMachineError) {
            return;
        }
        if (exchange.responded()) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING,
                "Error in {0} {1} on route {2} before an answer; answering 500 and rethrowing",
                stage, componentClass, routeMatch.route().pathPattern());
        answerInternalError(exchange, err, routeMatch);
    }

    /**
     * Answers 500 on an exchange that is still unanswered. An exchange that was already answered is left
     * alone; one whose earlier answer was attempted and did not complete is answered once more, because the
     * kernel refuses a second answer only after it has claimed the first. {@code original} is the failure
     * being answered, or {@code null} when a filter or handler returned without answering. A failure of the
     * attempt is handled by {@link #answer}.
     */
    private static void answerInternalError(MatchedHttpExchange exchange, Throwable original, RouteMatch routeMatch) {
        if (exchange.responded()) {
            return;
        }
        answer(exchange, HttpStatus.INTERNAL_SERVER_ERROR, null, "unanswered or failed request", routeMatch,
                original);
    }

    /**
     * The one place the registry writes an answer of its own: a requirement denial, a tenant conflict, a 404,
     * a 405, a 503 or a 500. The write is attempted once, and one rule decides what its failure does.
     *
     * <ul>
     *   <li>A {@link VirtualMachineError} thrown by the write always propagates, with {@code original}
     *       attached to it as suppressed.</li>
     *   <li>When {@code original}, the failure being handled, is an {@link Error}, any other failure of the
     *       write is attached to it as suppressed and the caller rethrows {@code original}.</li>
     *   <li>Otherwise the failure of the write is logged once at ERROR, naming the status, what it answered,
     *       the route's path pattern when a route matched and the failure's class, never its message, and this
     *       method returns normally. The caller treats the exchange as finished: the handler does not run.</li>
     * </ul>
     *
     * @param response the response to write, or {@code null} to write a bare {@code status}
     * @param reason   what the registry was answering, for the log
     * @param routeMatch the matched route, or {@code null} when no route matched
     * @param original the failure being answered, or {@code null}
     */
    private static void answer(HttpExchange exchange, HttpStatus status, HttpResponse response, String reason,
                               RouteMatch routeMatch, Throwable original) {
        try {
            if (response != null) {
                exchange.respond(response);
            } else {
                exchange.respond(status);
            }
        } catch (VirtualMachineError fatal) {
            if (original != null && original != fatal) {
                fatal.addSuppressed(original);
            }
            throw fatal;
        } catch (RuntimeException | Error writeFailure) {
            if (original instanceof Error err) {
                if (err != writeFailure) {
                    err.addSuppressed(writeFailure);
                }
            } else if (routeMatch != null) {
                LOG.log(System.Logger.Level.ERROR,
                        "Answering {0} ({1}) failed with {2} on route {3}; the exchange is abandoned",
                        status.code(), reason, writeFailure.getClass().getName(), routeMatch.route().pathPattern());
            } else {
                LOG.log(System.Logger.Level.ERROR,
                        "Answering {0} ({1}) failed with {2}; the exchange is abandoned",
                        status.code(), reason, writeFailure.getClass().getName());
            }
        }
    }

    /**
     * Runs the route's handler inside the admitted context and guarantees the exchange is answered when it
     * returns: a handler that returns without responding is answered 500. A handler answers before it returns;
     * an answer written afterwards is not part of this contract.
     */
    private static void runHandler(RouteMatch match, MatchedHttpExchange exchange, RequestContext admitted) {
        try {
            DefaultRequestContext.bind(admitted, () -> {
                match.route().handler().handle(exchange);
                return null;
            });
        } catch (Error err) {
            failOnError(err, exchange, "handler", match.route().handler().getClass().getName(), match);
            throw err;
        }
        if (!exchange.responded()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Handler {0} returned on route {1} without answering; answering 500",
                    match.route().handler().getClass().getName(), match.route().pathPattern());
            answerInternalError(exchange, null, match);
        }
    }

    @Override
    public Optional<RouteMatch> matchRoute(HttpMethod method, String path) {
        return Optional.ofNullable(findMatch(method, path));
    }

    /**
     * The match for the request, or {@code null} when no route serves the method and path. The query string, if
     * any, is not part of the path matched.
     */
    private RouteMatch findMatch(HttpMethod method, String path) {
        if (method == null || path == null) {
            return null;
        }
        int query = path.indexOf('?');
        int end = query >= 0 ? query : path.length();
        // A route with path parameters is matched by a regular expression and extracts its values as substrings,
        // so it needs the path as a String of its own; that string is cut only when such a route is reached.
        // A route without parameters only compares, and compares the region in place.
        String cleanPath = query >= 0 ? null : path;

        RegisteredRoute exactMatch = null;
        ApiSurfaceRoute parameterizedMatch = null;
        Map<String, String> extractedParams = null;

        for (RegisteredRoute entry : routeList) {
            ApiSurfaceRoute route = entry.route();
            if (!route.matchesMethod(method)) {
                continue;
            }
            if (entry.literalMatch() != null) {
                if (matchesLiteral(route.pathPattern(), path, end)) {
                    // Exact match takes precedence immediately
                    exactMatch = entry;
                    break;
                }
            } else {
                if (cleanPath == null) {
                    cleanPath = path.substring(0, end);
                }
                if (route.matchesPath(cleanPath) && parameterizedMatch == null) {
                    parameterizedMatch = route;
                    extractedParams = route.extractPathParams(cleanPath);
                }
            }
        }

        if (exactMatch != null) {
            return exactMatch.literalMatch();
        }
        if (parameterizedMatch != null) {
            return new RouteMatch(parameterizedMatch, extractedParams != null ? extractedParams : Map.of());
        }
        return null;
    }

    /**
     * Whether {@code path[0, end)} is, after normalisation, the pattern of a route without parameters. The
     * normalisation is the one {@link ApiSurfaceRoute#matchesPath} applies: surrounding whitespace (every
     * character up to a space) is trimmed, a leading slash is added when missing, and one trailing slash is
     * dropped from a result longer than one character. The comparison is made on the region, so no substring
     * is created. Any change to that normalisation must be made in both places.
     */
    private static boolean matchesLiteral(String pattern, String path, int end) {
        int from = 0;
        while (from < end && path.charAt(from) <= ' ') {
            from++;
        }
        int to = end;
        while (to > from && path.charAt(to - 1) <= ' ') {
            to--;
        }
        boolean leadingSlash = to > from && path.charAt(from) == '/';
        int length = (to - from) + (leadingSlash ? 0 : 1);
        if (length > 1 && path.charAt(to - 1) == '/') {
            length--;
        }
        if (pattern.length() != length) {
            return false;
        }
        return leadingSlash
                ? pattern.regionMatches(0, path, from, length)
                : pattern.charAt(0) == '/' && pattern.regionMatches(1, path, from, length - 1);
    }

    @Override
    public List<ApiSurfaceRoute> routes() {
        List<ApiSurfaceRoute> routes = new ArrayList<>(routeList.size());
        for (RegisteredRoute entry : routeList) {
            routes.add(entry.route());
        }
        return Collections.unmodifiableList(routes);
    }

    @Override
    public int routeCount() {
        return routeList.size();
    }

    @Override
    public void clear() {
        synchronized (mutationLock) {
            checkRemovalAllowed();
            routeList.clear();
            filterList.clear();
            orderedFilters = NO_FILTERS;
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        if (phase == Phase.TEARDOWN) {
            answer(exchange, HttpStatus.SERVICE_UNAVAILABLE, null, "registry is tearing down", null, null);
            return;
        }
        String rawPath = exchange.request().path();
        HttpMethod method = exchange.request().method();

        RouteMatch match = findMatch(method, rawPath);
        if (match != null) {
            MatchedHttpExchange effectiveExchange = new MatchedHttpExchange(exchange, match.pathParams());
            boolean handlerStarted = false;
            try {
                DefaultAdmissionContext admission = DefaultAdmissionContext.open();
                if (!admit(effectiveExchange, match, admission)) {
                    return;
                }
                admission.seal();
                RequestContext admitted = admission.requestContext();
                HttpStatus denial = unmet(match.route(), admitted);
                if (denial != null) {
                    // Whether or not the write succeeds, the request is over: the handler never runs.
                    answer(effectiveExchange, denial, null, "unmet route requirement", match, null);
                    return;
                }
                handlerStarted = true;
                runHandler(match, effectiveExchange, admitted);
            } catch (TenantConflictException ex) {
                LOG.log(System.Logger.Level.DEBUG, "Tenant conflict in route processing", ex);
                if (!effectiveExchange.responded()) {
                    answer(effectiveExchange, HttpStatus.FORBIDDEN, null, "tenant conflict", match, null);
                }
            } catch (Exception ex) {
                if (handlerStarted) {
                    LOG.log(System.Logger.Level.ERROR, "Handler {0} threw {1} on route {2}",
                            match.route().handler().getClass().getName(), ex.getClass().getName(),
                            match.route().pathPattern());
                } else {
                    LOG.log(System.Logger.Level.ERROR, "Admission of route {0} threw {1}",
                            match.route().pathPattern(), ex.getClass().getName());
                }
                answerInternalError(effectiveExchange, ex, match);
            }
            return;
        }

        // Check if path matches for any other HTTP method
        String cleanPath = stripQueryString(rawPath);
        Set<String> allowedMethods = new LinkedHashSet<>();
        for (RegisteredRoute entry : routeList) {
            if (entry.route().matchesPath(cleanPath)) {
                allowedMethods.add(entry.route().method().name());
            }
        }

        if (!allowedMethods.isEmpty()) {
            String allowHeaderValue = String.join(", ", allowedMethods);
            List<HttpHeader> headers = List.of(new HttpHeader("Allow", allowHeaderValue));
            HttpVersion version = exchange.request().version() != null ? exchange.request().version() : HttpVersion.HTTP_1_1;
            answer(exchange, HttpStatus.METHOD_NOT_ALLOWED,
                    new HttpResponse(HttpStatus.METHOD_NOT_ALLOWED, version, headers, null),
                    "method not allowed for a matched path", null, null);
        } else {
            answer(exchange, HttpStatus.NOT_FOUND, null, "no route matches the path", null, null);
        }
    }

    /**
     * Evaluates the route's requirements against the admitted principal.
     *
     * @return the status that answers the request, or {@code null} when every requirement is met
     */
    private static HttpStatus unmet(ApiSurfaceRoute route, RequestContext admitted) {
        PrincipalContext principal = admitted.principal().orElse(null);
        List<RouteRequirement> requirements = route.requirements();
        for (int i = 0, count = requirements.size(); i < count; i++) {
            RouteRequirement requirement = requirements.get(i);
            // An exhaustive switch expression: a requirement kind added to the SPI is a compile error here
            // (and a MatchException, answered 500, against a newer SPI), never a requirement that is skipped.
            HttpStatus denial = switch (requirement.kind()) {
                case PERMIT_ALL -> null;
                case AUTHENTICATED -> principal == null ? HttpStatus.UNAUTHORIZED : null;
                case ANY_SCOPE -> principal == null ? HttpStatus.UNAUTHORIZED
                        : holdsAnyScope(principal, requirement) ? null : HttpStatus.FORBIDDEN;
                case ALL_SCOPES -> principal == null ? HttpStatus.UNAUTHORIZED
                        : holdsAllScopes(principal, requirement) ? null : HttpStatus.FORBIDDEN;
                // An abstention is refused at route construction; reaching it here is a defect, not a permission.
                case ABSTAIN -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            if (denial != null) {
                return denial;
            }
        }
        return null;
    }

    private static boolean holdsAnyScope(PrincipalContext principal, RouteRequirement requirement) {
        for (int i = 0; i < requirement.scopeCount(); i++) {
            if (principal.hasScope(requirement.scopeAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsAllScopes(PrincipalContext principal, RouteRequirement requirement) {
        for (int i = 0; i < requirement.scopeCount(); i++) {
            if (!principal.hasScope(requirement.scopeAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The path pattern with every parameter name erased. Two patterns with the same shape match exactly the
     * same paths, so only one of them can ever be served and the other must not be registered.
     */
    private static String shape(String pathPattern) {
        return PARAM_SEGMENT.matcher(pathPattern).replaceAll("{}");
    }

    private static String stripQueryString(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}
