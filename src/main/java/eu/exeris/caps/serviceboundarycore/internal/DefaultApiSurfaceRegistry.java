package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
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

    public DefaultApiSurfaceRegistry() {
    }

    @Override
    public void registerRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        synchronized (routeList) {
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
        return routeList.removeIf(existing -> existing.equals(route) && existing.handler() == route.handler());
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
        synchronized (filterList) {
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
        return filterList.remove(filter);
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
        List<AdmissionFilter> orderedFilters = filters();
        for (AdmissionFilter filter : orderedFilters) {
            try {
                boolean proceed = DefaultAdmissionContext.callWith(admission,
                        () -> DefaultRequestContext.bind(admission.requestContext(),
                                () -> filter.filter(tracked, routeMatch)));
                if (!proceed) {
                    LOG.log(System.Logger.Level.DEBUG, "Filter {0} halted admission pipeline", filter.name());
                    return false;
                }
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Unhandled exception in admission filter " + filter.name(), ex);
                if (!tracked.responded()) {
                    tracked.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                return false;
            }
        }
        return true;
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
        routeList.clear();
        filterList.clear();
    }

    @Override
    public void handle(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
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
                DefaultRequestContext.bind(admitted, () -> {
                    match.route().handler().handle(effectiveExchange);
                    return null;
                });
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Unhandled exception in route handler for " + match.route(), ex);
                if (!effectiveExchange.responded()) {
                    exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                }
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
