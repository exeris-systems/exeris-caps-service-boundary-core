package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.List;
import java.util.Optional;

/**
 * The external HTTP route and action registry for Service Boundary SKUs.
 *
 * <p>Emitted handlers from {@code rest-emission} and {@code graphql-emission}
 * capabilities register their routes directly with this registry. Acting as a
 * kernel {@link HttpHandler}, this registry dispatches inbound HTTP exchanges to
 * the registered handlers with path parameter extraction and standard HTTP status
 * enforcement.
 *
 * @since 0.1.0
 */
public interface ApiSurfaceRegistry extends HttpHandler {

    /**
     * Registers a route on the service boundary.
     *
     * @param route the route definition to register; never {@code null}
     */
    void registerRoute(ApiSurfaceRoute route);

    /**
     * Unregisters a previously registered route.
     *
     * @param route the route definition to unregister; never {@code null}
     * @return {@code true} if the route was removed, {@code false} otherwise
     */
    boolean unregisterRoute(ApiSurfaceRoute route);

    /**
     * Attempts to match an HTTP method and path against registered routes.
     *
     * @param method the HTTP method
     * @param path   the request path
     * @return the matched route and extracted parameters, or empty if no route matches
     */
    Optional<RouteMatch> matchRoute(HttpMethod method, String path);

    /**
     * Returns an unmodifiable snapshot of all currently registered routes.
     *
     * @return list of registered routes
     */
    List<ApiSurfaceRoute> routes();

    /**
     * Returns the number of registered routes.
     *
     * @return route count
     */
    int routeCount();

    /**
     * Removes all registered routes from this registry.
     */
    void clear();

    /**
     * Registers an admission filter into the request pipeline.
     *
     * @param filter admission filter to register; never {@code null}
     */
    default void registerFilter(AdmissionFilter filter) {}

    default boolean unregisterFilter(AdmissionFilter filter) {
        return false;
    }

    default List<AdmissionFilter> filters() {
        return List.of();
    }

    default RequestFilterChain filterChain() {
        return RequestFilterChain.empty();
    }

    /**
     * Dispatches an incoming HTTP exchange to the matched route handler, or responds
     * with 404 (Not Found) or 405 (Method Not Allowed) if no route matches.
     *
     * @param exchange the incoming HTTP exchange
     */
    @Override
    void handle(HttpExchange exchange);
}

