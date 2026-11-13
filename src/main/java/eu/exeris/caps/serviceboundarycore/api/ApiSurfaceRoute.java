package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.caps.serviceboundarycore.internal.PathPattern;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.RouteRequirement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An HTTP route definition on the service boundary.
 *
 * <p>Binds an HTTP method and path pattern to a target {@link HttpHandler} and
 * required authorization rules ({@link RouteRequirement}). {@link ApiSurfaceRegistry} enforces the
 * requirements after the admission filters ran: a route with a requirement other than
 * {@link RouteRequirement#permitAll()} is never served to a request without an authenticated principal,
 * so a route that declares requirements fails closed when no filter authenticates.
 * {@link RouteRequirement#abstain()} declares nothing about a route and is refused.
 *
 * <p>The requirements of a route are conjunctive: each one is evaluated in order and the first one that is
 * not met denies the request. {@link RouteRequirement#permitAll()} is always met, so mixed with another
 * requirement it adds nothing: the other requirement is enforced, and the route is not anonymous. A list
 * containing {@code permitAll()} is non-empty, so it satisfies strict route mode.
 *
 * <p>Path patterns support parameterized segments such as {@code /api/v1/orders/{id}}
 * or {@code /users/{userId}/addresses/{addressId}}.
 *
 * @since 0.1.0
 */
public final class ApiSurfaceRoute {

    private final HttpMethod method;
    private final String pathPattern;
    private final String actionName;
    private final List<RouteRequirement> requirements;
    private final HttpHandler handler;

    private final PathPattern compiled;
    private final List<String> paramNames;

    /**
     * Creates a route.
     *
     * @param method       HTTP method; non-null
     * @param pathPattern  path pattern, optionally with {@code {name}} segments; non-null
     * @param actionName   name of the action the route dispatches to; non-null
     * @param requirements what the route demands of the caller; {@code null} means none
     * @param handler      handler that serves the route; non-null
     * @throws IllegalArgumentException if a requirement is {@link RouteRequirement#abstain()}
     */
    public ApiSurfaceRoute(HttpMethod method,
                           String pathPattern,
                           String actionName,
                           List<RouteRequirement> requirements,
                           HttpHandler handler) {
        this.method = Objects.requireNonNull(method, "method");
        this.pathPattern = normalizePath(Objects.requireNonNull(pathPattern, "pathPattern"));
        this.actionName = Objects.requireNonNull(actionName, "actionName");
        this.requirements = requirements != null ? List.copyOf(requirements) : List.of();
        for (RouteRequirement requirement : this.requirements) {
            if (requirement.isAbstention()) {
                throw new IllegalArgumentException("A route cannot abstain from declaring what it requires");
            }
        }
        this.handler = Objects.requireNonNull(handler, "handler");

        this.compiled = new PathPattern(this.pathPattern);
        this.paramNames = this.compiled.names();
    }

    /** Creates a builder for a route. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the HTTP method this route serves. */
    public HttpMethod method() {
        return method;
    }

    /** Returns the normalised path pattern. */
    public String pathPattern() {
        return pathPattern;
    }

    /** Returns the name of the action this route dispatches to. */
    public String actionName() {
        return actionName;
    }

    /** Returns what the route demands of the caller, enforced by the registry after admission. */
    public List<RouteRequirement> requirements() {
        return requirements;
    }

    /** Returns the handler that serves this route. */
    public HttpHandler handler() {
        return handler;
    }

    /** Returns the names of the path parameters, in pattern order. */
    public List<String> paramNames() {
        return paramNames;
    }

    /** Reports whether this route serves the given HTTP method. */
    public boolean matchesMethod(HttpMethod method) {
        return this.method.equals(method);
    }

    /** Reports whether the given path matches this route's pattern. */
    public boolean matchesPath(String path) {
        if (path == null) {
            return false;
        }
        if (paramNames.isEmpty()) {
            return this.pathPattern.equals(normalizePath(path));
        }
        return compiled.matches(path, path.length());
    }

    /** Extracts the path parameters from a path that matches this route. */
    public Map<String, String> extractPathParams(String path) {
        if (path == null || paramNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = compiled.match(path, path.length());
        if (values == null) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : paramNames) {
            params.put(name, values.get(name));
        }
        return Collections.unmodifiableMap(params);
    }

    /**
     * Trims whitespace, adds a leading slash and drops one trailing slash from a result longer than one
     * character. {@code PathPattern.region} applies the same rule to a region of the request path, without building
     * the string, for every match made in place, so a change here must be made there too.
     */
    private static String normalizePath(String path) {
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiSurfaceRoute that)) return false;
        return method == that.method &&
                pathPattern.equals(that.pathPattern) &&
                actionName.equals(that.actionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, pathPattern, actionName);
    }

    @Override
    public String toString() {
        return "ApiSurfaceRoute[" + method + " " + pathPattern + " -> " + actionName + "]";
    }

    /** Fluent builder for {@link ApiSurfaceRoute}. */
    public static final class Builder {
        private HttpMethod method = HttpMethod.GET;
        private String pathPattern;
        private String actionName = "";
        private List<RouteRequirement> requirements = List.of();
        private HttpHandler handler;

        /** Sets the HTTP method; defaults to GET. */
        public Builder method(HttpMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /** Sets the path pattern; required. */
        public Builder pathPattern(String pathPattern) {
            this.pathPattern = Objects.requireNonNull(pathPattern, "pathPattern");
            return this;
        }

        /** Sets the action name; defaults to the empty string. */
        public Builder actionName(String actionName) {
            this.actionName = Objects.requireNonNull(actionName, "actionName");
            return this;
        }

        /** Sets what the route demands of the caller; {@code null} means none. */
        public Builder requirements(List<RouteRequirement> requirements) {
            this.requirements = requirements != null ? List.copyOf(requirements) : List.of();
            return this;
        }

        /** Sets the handler; required. */
        public Builder handler(HttpHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Builds the route. */
        public ApiSurfaceRoute build() {
            return new ApiSurfaceRoute(method, pathPattern, actionName, requirements, handler);
        }
    }
}
