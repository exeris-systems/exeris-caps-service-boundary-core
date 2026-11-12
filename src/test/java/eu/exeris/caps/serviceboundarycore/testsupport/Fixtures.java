package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Principals, routes and filters shared by the admission-state tests. */
public final class Fixtures {

    private Fixtures() { }

    /** A principal with an optional tenant and a set of scopes. */
    public record Person(UUID principalId, Optional<UUID> tenantId, Set<String> scopes) implements PrincipalContext {
        @Override public Set<String> roles() { return Set.of(); }
    }

    /** A principal whose {@link #tenantId()} cannot be answered. */
    public record TenantFails(UUID principalId) implements PrincipalContext {
        @Override public Optional<UUID> tenantId() { throw new IllegalArgumentException("no tenant available"); }

        @Override public Set<String> roles() { return Set.of(); }

        @Override public Set<String> scopes() { return Set.of(); }
    }

    /** A principal whose text form carries a credential-looking string, to prove it is never copied out. */
    public record Credentialed(UUID principalId, String secret) implements PrincipalContext {
        @Override public Optional<UUID> tenantId() { return Optional.empty(); }

        @Override public Set<String> roles() { return Set.of("role-" + secret); }

        @Override public Set<String> scopes() { return Set.of("scope-" + secret); }
    }

    public static Person person(long n) {
        return new Person(new UUID(0xABCDL, n), Optional.empty(), Set.of());
    }

    public static Person person(long n, UUID tenant) {
        return new Person(new UUID(0xABCDL, n), Optional.of(tenant), Set.of());
    }

    public static Person person(long n, String... scopes) {
        return new Person(new UUID(0xABCDL, n), Optional.empty(), Set.of(scopes));
    }

    public static HttpHandler ok() {
        return ex -> ex.respond(HttpStatus.OK);
    }

    public static ApiSurfaceRoute route(HttpMethod method, String pattern, List<RouteRequirement> requirements,
                                        HttpHandler handler) {
        return new ApiSurfaceRoute(method, pattern, "Action#" + method + pattern, requirements, handler);
    }

    public static ApiSurfaceRoute open(String pattern) {
        return route(HttpMethod.GET, pattern, List.of(RouteRequirement.permitAll()), ok());
    }

    public static ApiSurfaceRoute open(String pattern, HttpHandler handler) {
        return route(HttpMethod.GET, pattern, List.of(RouteRequirement.permitAll()), handler);
    }

    /** A filter body that may use the exchange and the match. */
    public static AdmissionFilter filter(String name, AdmissionPhase phase, Consumer<RouteMatch> body) {
        return new AdmissionFilter() {
            @Override public String name() { return name; }

            @Override public AdmissionPhase phase() { return phase; }

            @Override
            public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
                body.accept(routeMatch);
                return true;
            }
        };
    }

    /** A filter whose name and phase can be changed after it was registered. */
    public static final class Shifty implements AdmissionFilter {
        public volatile String name;
        public volatile AdmissionPhase phase;
        private final Consumer<String> onRun;

        public Shifty(String name, AdmissionPhase phase, Consumer<String> onRun) {
            this.name = name;
            this.phase = phase;
            this.onRun = onRun;
        }

        @Override public String name() { return name; }

        @Override public AdmissionPhase phase() { return phase; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            onRun.accept(name);
            return true;
        }
    }
}
