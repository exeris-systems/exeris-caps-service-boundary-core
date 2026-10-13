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
     * @throws IllegalArgumentException if the filter name is null or blank, or its phase is null
     * @throws IllegalStateException    if a filter with the same name is already registered
     */
    void registerFilter(AdmissionFilter filter);

    /**
     * Removes a previously registered admission filter.
     *
     * @param filter the filter to remove; never {@code null}
     * @return {@code true} if the filter was removed, {@code false} otherwise
     */
    boolean unregisterFilter(AdmissionFilter filter);

    /**
     * Returns the registered admission filters in execution order.
     *
     * @return unmodifiable snapshot ordered by phase, then by name
     */
    List<AdmissionFilter> filters();

    /**
     * Returns the request filter chain that runs this registry's admission filters.
     *
     * @return the filter chain; never {@code null}
     */
    RequestFilterChain filterChain();

    /**
     * Dispatches an incoming HTTP exchange to the matched route handler, or responds
     * with 404 (Not Found) or 405 (Method Not Allowed) if no route matches.
     *
     * <p>Between route match and handler the registered {@link AdmissionFilter}s run in order, each
     * with an {@link AdmissionContext} bound. The handler runs only when every filter passed, and it
     * runs inside the {@link RequestContext} the filters established: its principal is the one an
     * admission filter verified, never one taken from the request itself. Before the handler runs, every
     * {@link ApiSurfaceRoute#requirements() route requirement} is enforced against that principal: an
     * unauthenticated request is answered 401 and an authenticated one lacking a required scope 403.
     *
     * <p>Requests that match no route are answered 404 or 405 without running any filter.
     *
     * @param exchange the incoming HTTP exchange
     */
    @Override
    void handle(HttpExchange exchange);
}

