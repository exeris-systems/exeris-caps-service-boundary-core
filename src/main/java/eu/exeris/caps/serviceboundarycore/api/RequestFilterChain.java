package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;

import java.util.List;

/**
 * Ordered pipeline of {@link AdmissionFilter} instances executed before route handlers (ADR-099).
 *
 * @since 0.1.0
 */
public interface RequestFilterChain {

    /**
     * Registers an admission filter into the chain.
     *
     * @param filter filter to register; non-null
     */
    void registerFilter(AdmissionFilter filter);

    /**
     * Unregisters a previously registered filter.
     *
     * @param filter filter to unregister; non-null
     * @return {@code true} if removed, {@code false} otherwise
     */
    boolean unregisterFilter(AdmissionFilter filter);

    /**
     * Returns an unmodifiable view of all registered filters, sorted deterministically by phase then name.
     *
     * @return ordered filter list
     */
    List<AdmissionFilter> filters();

    /**
     * Executes the filter chain for the given exchange and matched route.
     *
     * @param exchange incoming HTTP exchange
     * @param routeMatch matched route definition
     * @return {@code true} if all filters passed and handler should be invoked, {@code false} if a filter short-circuited
     */
    boolean execute(HttpExchange exchange, RouteMatch routeMatch);

    /**
     * Returns a no-op empty filter chain.
     *
     * @return empty filter chain
     */
    static RequestFilterChain empty() {
        return new RequestFilterChain() {
            @Override public void registerFilter(AdmissionFilter filter) {}
            @Override public boolean unregisterFilter(AdmissionFilter filter) { return false; }
            @Override public List<AdmissionFilter> filters() { return List.of(); }
            @Override public boolean execute(HttpExchange exchange, RouteMatch routeMatch) { return true; }
        };
    }
}
