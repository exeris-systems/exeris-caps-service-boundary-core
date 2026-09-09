package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.RouteRequirement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An HTTP route definition on the service boundary.
 *
 * <p>Binds an HTTP method and path pattern to a target {@link HttpHandler} and
 * required authorization rules ({@link RouteRequirement}).
 *
 * <p>Path patterns support parameterized segments such as {@code /api/v1/orders/{id}}
 * or {@code /users/{userId}/addresses/{addressId}}.
 *
 * @since 0.1.0
 */
public final class ApiSurfaceRoute {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    private final HttpMethod method;
    private final String pathPattern;
    private final String actionName;
    private final List<RouteRequirement> requirements;
    private final HttpHandler handler;

    private final Pattern compiledRegex;
    private final List<String> paramNames;

    public ApiSurfaceRoute(HttpMethod method,
                           String pathPattern,
                           String actionName,
                           List<RouteRequirement> requirements,
                           HttpHandler handler) {
        this.method = Objects.requireNonNull(method, "method");
        this.pathPattern = normalizePath(Objects.requireNonNull(pathPattern, "pathPattern"));
        this.actionName = Objects.requireNonNull(actionName, "actionName");
        this.requirements = requirements != null ? List.copyOf(requirements) : List.of();
        this.handler = Objects.requireNonNull(handler, "handler");

        // Compile regex for parameterized path matching
        List<String> names = new ArrayList<>();
        Matcher m = PARAM_PATTERN.matcher(this.pathPattern);
        StringBuilder regexBuilder = new StringBuilder("^");
        int lastEnd = 0;
        while (m.find()) {
            regexBuilder.append(Pattern.quote(this.pathPattern.substring(lastEnd, m.start())));
            regexBuilder.append("([^/]+)");
            names.add(m.group(1));
            lastEnd = m.end();
        }
        regexBuilder.append(Pattern.quote(this.pathPattern.substring(lastEnd)));
        regexBuilder.append("$");

        this.paramNames = Collections.unmodifiableList(names);
        this.compiledRegex = Pattern.compile(regexBuilder.toString());
    }

    public static Builder builder() {
        return new Builder();
    }

    public HttpMethod method() {
        return method;
    }

    public String pathPattern() {
        return pathPattern;
    }

    public String actionName() {
        return actionName;
    }

    public List<RouteRequirement> requirements() {
        return requirements;
    }

    public HttpHandler handler() {
        return handler;
    }

    public List<String> paramNames() {
        return paramNames;
    }

    public boolean matchesMethod(HttpMethod method) {
        return this.method.equals(method);
    }

    public boolean matchesPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalizePath(path);
        if (paramNames.isEmpty()) {
            return this.pathPattern.equals(normalized);
        }
        return compiledRegex.matcher(normalized).matches();
    }

    public Map<String, String> extractPathParams(String path) {
        if (path == null || paramNames.isEmpty()) {
            return Map.of();
        }
        String normalized = normalizePath(path);
        Matcher matcher = compiledRegex.matcher(normalized);
        if (!matcher.matches()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), matcher.group(i + 1));
        }
        return Collections.unmodifiableMap(params);
    }

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

    public static final class Builder {
        private HttpMethod method = HttpMethod.GET;
        private String pathPattern;
        private String actionName = "";
        private List<RouteRequirement> requirements = List.of();
        private HttpHandler handler;

        public Builder method(HttpMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        public Builder pathPattern(String pathPattern) {
            this.pathPattern = Objects.requireNonNull(pathPattern, "pathPattern");
            return this;
        }

        public Builder actionName(String actionName) {
            this.actionName = Objects.requireNonNull(actionName, "actionName");
            return this;
        }

        public Builder requirements(List<RouteRequirement> requirements) {
            this.requirements = requirements != null ? List.copyOf(requirements) : List.of();
            return this;
        }

        public Builder handler(HttpHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public ApiSurfaceRoute build() {
            return new ApiSurfaceRoute(method, pathPattern, actionName, requirements, handler);
        }
    }
}
