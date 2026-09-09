package eu.exeris.caps.serviceboundarycore.api;

import java.util.Map;
import java.util.Objects;

/**
 * Result of matching an incoming HTTP request against an {@link ApiSurfaceRoute}.
 *
 * @param route      the matched route; never {@code null}
 * @param pathParams the extracted path parameters; never {@code null}
 * @since 0.1.0
 */
public record RouteMatch(ApiSurfaceRoute route, Map<String, String> pathParams) {

    public RouteMatch {
        Objects.requireNonNull(route, "route");
        pathParams = pathParams != null ? Map.copyOf(pathParams) : Map.of();
    }
}
