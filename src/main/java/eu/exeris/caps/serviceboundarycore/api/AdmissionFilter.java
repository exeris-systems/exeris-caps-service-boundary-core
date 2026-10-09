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
     * @return {@code true} to proceed to the next filter/handler, or {@code false} if request handling was completed
     */
    boolean filter(HttpExchange exchange, RouteMatch routeMatch);
}
