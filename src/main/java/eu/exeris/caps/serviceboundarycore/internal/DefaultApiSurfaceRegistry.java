package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link ApiSurfaceRegistry}.
 *
 * <p>Thread-safe route registry and dispatcher for Service Boundary SKUs.
 *
 * @since 0.1.0
 */
public final class DefaultApiSurfaceRegistry implements ApiSurfaceRegistry {

    private static final System.Logger LOG = System.getLogger(DefaultApiSurfaceRegistry.class.getName());

    private final List<ApiSurfaceRoute> routeList = new CopyOnWriteArrayList<>();

    public DefaultApiSurfaceRegistry() {
    }

    @Override
    public void registerRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        routeList.add(route);
        LOG.log(System.Logger.Level.DEBUG, "Registered boundary route: {0}", route);
    }

    @Override
    public boolean unregisterRoute(ApiSurfaceRoute route) {
        Objects.requireNonNull(route, "route");
        return routeList.remove(route);
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
            HttpExchange effectiveExchange = match.pathParams().isEmpty()
                    ? exchange
                    : new MatchedHttpExchange(exchange, match.pathParams());
            try {
                match.route().handler().handle(effectiveExchange);
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Unhandled exception in route handler for " + match.route(), ex);
                exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
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

    private static String stripQueryString(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}
