package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;

/**
 * An admission filter executed between route matching and action dispatch on the Service Boundary (ADR-099).
 *
 * <p>Admission filters run in deterministic order: ordered first by {@link #phase()} and then
 * lexicographically by {@link #name()} within the same phase.
 *
 * @since 0.1.0
 */
public interface AdmissionFilter {

    /**
     * Unique name of the filter, used for deterministic lexicographical ordering within its phase.
     *
     * @return non-null unique filter name
     */
    String name();

    /**
     * The admission phase in which this filter executes.
     *
     * @return non-null admission phase
     */
    AdmissionPhase phase();

    /**
     * Evaluates the incoming request and matched route.
     *
     * @param exchange incoming HTTP exchange
     * @param routeMatch matched route definition and extracted parameters
     * <p>Once the exchange is answered, admission is over. The registry reads whether the exchange has been
     * answered after each filter returns; if it has, the remaining filters and the handler do not run,
     * whatever this method returned. A filter that answered and returned {@code true} violates this contract
     * and is logged once at WARNING, naming the route's path pattern and the filter's class. A filter must not
     * call {@code respond} a second time: the kernel's exchange refuses it with {@link IllegalStateException}.
     *
     * @return {@code true} to proceed to the next filter/handler, or {@code false} when the filter has
     *         answered the exchange; a filter that returns {@code false} without having responded has the
     *         exchange answered 500 by the registry
     */
    boolean filter(HttpExchange exchange, RouteMatch routeMatch);
}
