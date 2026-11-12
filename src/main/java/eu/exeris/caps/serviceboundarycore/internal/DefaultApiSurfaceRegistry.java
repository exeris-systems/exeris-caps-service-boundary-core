package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
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
 * <p>No matched exchange is left unanswered. A filter that returns {@code false} without responding, and a
 * handler that returns without responding, are answered 500 by the registry; a filter or handler that throws
 * an {@link Exception} is answered 500; one that throws an {@link Error} is answered 500 when still unanswered
 * and the error is rethrown (a {@link VirtualMachineError} is rethrown without an attempt to answer). An
 * exchange on which {@code respond} was already called is never answered again by the registry.
 *
 * <p>Once an exchange is answered, admission is over. After each filter returns, the registry reads whether
 * the exchange has been answered; if it has, the remaining filters and the handler do not run, whatever the
 * filter returned. A filter that answered and returned {@code true} is a contract violation and is logged
 * once at WARNING. A second {@code respond} issued by a filter or handler is refused by the kernel's exchange
 * with {@link IllegalStateException}; the registry adds no guard of its own.
 *
 * <p>When the registry's own attempt to answer 500 fails, one rule holds for filters and handlers alike. If the
 * original failure was an {@link Error}, that error leaves {@code handle} with the answering failure attached
 * as suppressed. Otherwise the answering failure is logged once at ERROR by class name and {@code handle}
 * returns normally, since nothing more can be done for the exchange. A {@link VirtualMachineError} thrown by
 * the answering attempt always propagates.
 *
 * <p>The registry moves one way through three phases: {@code OPEN}, {@code FROZEN}, {@code TEARDOWN}
 * ({@code OPEN} may go straight to {@code TEARDOWN}). While {@code OPEN} routes and filters may be added and
 * removed. While {@code FROZEN} the service is serving and nothing may be added, replaced or removed. In
 * {@code TEARDOWN} serving has ended: every request is answered 503, removal is permitted so that capabilities
 * can detach, and addition stays refused. Every mutator and every phase transition runs under one lock, so a
 * mutation either happens before a transition or observes it; the request path never takes that lock.
 *
 * <p>A request reads the filter snapshot before it reads the phase, and a removal is permitted only after the
 * {@code TEARDOWN} write it observed under the lock. A request that does not observe {@code TEARDOWN} therefore
 * read its snapshot before any removal, and runs every filter that was registered when serving ended.
 *
 * @since 0.1.0
 */
public final class DefaultApiSurfaceRegistry implements ApiSurfaceRegistry, RequestFilterChain {

    private static final System.Logger LOG = System.getLogger(DefaultApiSurfaceRegistry.class.getName());

    private static final Pattern PARAM_SEGMENT = Pattern.compile("\\{[a-zA-Z0-9_]+\\}");

    private static final Comparator<AdmissionFilter> FILTER_COMPARATOR = Comparator
            .comparing(AdmissionFilter::phase)
            .thenComparing(AdmissionFilter::name);

    private final List<ApiSurfaceRoute> routeList = new CopyOnWriteArrayList<>();
    private final List<AdmissionFilter> filterList = new CopyOnWriteArrayList<>();
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
            for (ApiSurfaceRoute existing : routeList) {
                if (existing.method() == route.method()
                        && shape(existing.pathPattern()).equals(shape(route.pathPattern()))) {
                    throw new IllegalStateException("A route is already registered for " + route.method()
                            + " " + route.pathPattern());
                }
            }
            routeList.add(route);
        }
        LOG.log(System.Logger.Level.DEBUG, "Registered boundary route: {0}", route);
    }

    @Override
    public boolean unregisterRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        synchronized (mutationLock) {
            checkRemovalAllowed();
            return routeList.removeIf(existing -> existing.equals(route) && existing.handler() == route.handler());
        }
    }

    @Override
    public void registerFilter(AdmissionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        String name = filter.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An admission filter needs a non-blank name");
        }
        if (filter.phase() == null) {
            throw new IllegalArgumentException("Admission filter " + name + " needs a phase");
        }
        synchronized (mutationLock) {
            checkAdditionAllowed();
            for (AdmissionFilter existing : filterList) {
                if (name.equals(existing.name())) {
                    throw new IllegalStateException("An admission filter named " + name + " is already registered");
                }
            }
            filterList.add(filter);
        }
        LOG.log(System.Logger.Level.DEBUG, "Registered admission filter: {0} ({1})", filter.name(), filter.phase());
    }

    @Override
    public boolean unregisterFilter(AdmissionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        synchronized (mutationLock) {
            checkRemovalAllowed();
            return filterList.remove(filter);
        }
    }

    @Override
    public List<AdmissionFilter> filters() {
        List<AdmissionFilter> copy = new ArrayList<>(filterList);
        copy.sort(FILTER_COMPARATOR);
        return Collections.unmodifiableList(copy);
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
        // The snapshot is taken before the phase is read: a removal is permitted only after teardown was
        // written, so a request that does not see teardown holds a snapshot that predates every removal.
        List<AdmissionFilter> orderedFilters = filters();
        if (phase == Phase.TEARDOWN) {
            if (!tracked.responded()) {
                tracked.respond(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return false;
        }
        for (AdmissionFilter filter : orderedFilters) {
            try {
                boolean proceed = DefaultAdmissionContext.callWith(admission,
                        () -> DefaultRequestContext.bind(admission.requestContext(),
                                () -> filter.filter(tracked, routeMatch)));
                if (tracked.responded()) {
                    if (proceed) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Admission filter {0} answered route {1} and returned true; admission ends",
                                filter.getClass().getName(), routeMatch.route().pathPattern());
                    } else {
                        LOG.log(System.Logger.Level.DEBUG, "Filter {0} halted admission pipeline", filter.name());
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
                LOG.log(System.Logger.Level.DEBUG, "Tenant conflict in admission filter {0}", filter.name());
                if (!tracked.responded()) {
                    tracked.respond(HttpStatus.FORBIDDEN);
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
     * Answers 500 on an exchange that is still unanswered, and decides what a failure of that attempt does.
     * An exchange that was already answered is left alone. {@code original} is the failure being answered, or
     * {@code null} when a filter or handler returned without answering.
     *
     * <ul>
     *   <li>A {@link VirtualMachineError} thrown by the attempt always propagates, with {@code original}
     *       attached to it as suppressed.</li>
     *   <li>When {@code original} is an {@link Error}, any other failure of the attempt is attached to it as
     *       suppressed and the caller rethrows {@code original}.</li>
     *   <li>Otherwise the failure of the attempt is logged once at ERROR by class name, without its message,
     *       and this method returns normally.</li>
     * </ul>
     */
    private static void answerInternalError(MatchedHttpExchange exchange, Throwable original, RouteMatch routeMatch) {
        if (exchange.responded()) {
            return;
        }
        try {
            exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (VirtualMachineError fatal) {
            if (original != null) {
                fatal.addSuppressed(original);
            }
            throw fatal;
        } catch (RuntimeException | Error answerFailure) {
            if (original instanceof Error err) {
                err.addSuppressed(answerFailure);
            } else {
                LOG.log(System.Logger.Level.ERROR,
                        "Answering 500 failed with {0} on route {1}; the exchange is abandoned",
                        answerFailure.getClass().getName(), routeMatch.route().pathPattern());
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
        if (method == null || path == null) {
            return Optional.empty();
        }
        String cleanPath = stripQueryString(path);

        ApiSurfaceRoute exactMatch = null;
        ApiSurfaceRoute parameterizedMatch = null;
        Map<String, String> extractedParams = null;

        for (ApiSurfaceRoute route : routeList) {
            if (route.matchesMethod(method) && route.matchesPath(cleanPath)) {
                if (route.paramNames().isEmpty()) {
                    // Exact match takes precedence immediately
                    exactMatch = route;
                    break;
                } else if (parameterizedMatch == null) {
                    parameterizedMatch = route;
                    extractedParams = route.extractPathParams(cleanPath);
                }
            }
        }

        if (exactMatch != null) {
            return Optional.of(new RouteMatch(exactMatch, Map.of()));
        }
        if (parameterizedMatch != null) {
            return Optional.of(new RouteMatch(parameterizedMatch, extractedParams != null ? extractedParams : Map.of()));
        }

        return Optional.empty();
    }

    @Override
    public List<ApiSurfaceRoute> routes() {
        return Collections.unmodifiableList(new ArrayList<>(routeList));
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
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        if (phase == Phase.TEARDOWN) {
            exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
            return;
        }
        String rawPath = exchange.request().path();
        String cleanPath = stripQueryString(rawPath);
        HttpMethod method = exchange.request().method();

        Optional<RouteMatch> matchOpt = matchRoute(method, cleanPath);
        if (matchOpt.isPresent()) {
            RouteMatch match = matchOpt.get();
            MatchedHttpExchange effectiveExchange = new MatchedHttpExchange(exchange, match.pathParams());
            try {
                DefaultAdmissionContext admission = DefaultAdmissionContext.open();
                if (!admit(effectiveExchange, match, admission)) {
                    return;
                }
                admission.seal();
                RequestContext admitted = admission.requestContext();
                HttpStatus denial = unmet(match.route(), admitted);
                if (denial != null) {
                    effectiveExchange.respond(denial);
                    return;
                }
                runHandler(match, effectiveExchange, admitted);
            } catch (TenantConflictException ex) {
                LOG.log(System.Logger.Level.DEBUG, "Tenant conflict in route processing", ex);
                if (!effectiveExchange.responded()) {
                    effectiveExchange.respond(HttpStatus.FORBIDDEN);
                }
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Handler {0} threw {1} on route {2}",
                        match.route().handler().getClass().getName(), ex.getClass().getName(),
                        match.route().pathPattern());
                answerInternalError(effectiveExchange, ex, match);
            }
            return;
        }

        // Check if path matches for any other HTTP method
        Set<String> allowedMethods = new LinkedHashSet<>();
        for (ApiSurfaceRoute route : routeList) {
            if (route.matchesPath(cleanPath)) {
                allowedMethods.add(route.method().name());
            }
        }

        if (!allowedMethods.isEmpty()) {
            String allowHeaderValue = String.join(", ", allowedMethods);
            List<HttpHeader> headers = List.of(new HttpHeader("Allow", allowHeaderValue));
            HttpVersion version = exchange.request().version() != null ? exchange.request().version() : HttpVersion.HTTP_1_1;
            exchange.respond(new HttpResponse(HttpStatus.METHOD_NOT_ALLOWED, version, headers, null));
        } else {
            exchange.respond(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Evaluates the route's requirements against the admitted principal.
     *
     * @return the status that answers the request, or {@code null} when every requirement is met
     */
    private static HttpStatus unmet(ApiSurfaceRoute route, RequestContext admitted) {
        PrincipalContext principal = admitted.principal().orElse(null);
        for (RouteRequirement requirement : route.requirements()) {
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
