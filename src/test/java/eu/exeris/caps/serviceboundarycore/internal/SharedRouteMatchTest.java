package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The match of a route without path parameters, shared between requests")
class SharedRouteMatchTest {

    @Test
    @DisplayName("every request and thread gets an equal match for the same route, with empty unmodifiable parameters")
    void matchEqualAcrossRequestsAndThreads() throws Exception {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ApiSurfaceRoute route = Fixtures.open("/health");
        registry.registerRoute(route);
        RouteMatch first = registry.matchRoute(HttpMethod.GET, "/health").orElseThrow();
        assertThat(first.route()).isSameAs(route);
        assertThat(first.pathParams()).isEmpty();
        assertThatThrownBy(() -> first.pathParams().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.pathParams().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.pathParams().remove("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.pathParams().putAll(Map.of("k", "v")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.pathParams().computeIfAbsent("k", k -> "v"))
                .isInstanceOf(UnsupportedOperationException.class);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<RouteMatch>>> futures = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                futures.add(pool.submit(() -> {
                    List<RouteMatch> seen = new ArrayList<>();
                    for (int i = 0; i < 2000; i++) {
                        seen.add(registry.matchRoute(HttpMethod.GET, i % 2 == 0 ? "/health" : "/health/?q=" + i)
                                .orElseThrow());
                    }
                    return seen;
                }));
            }
            for (Future<List<RouteMatch>> future : futures) {
                for (RouteMatch match : future.get(1, TimeUnit.MINUTES)) {
                    assertThat(match).isEqualTo(first);
                    assertThat(match.route()).isSameAs(route);
                    assertThat(match.pathParams()).isEmpty();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a handler cannot poison later requests: the exchange's path parameters, the route's lists and the match are immutable")
    void handlerCannotPoisonTheSharedMatch() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<Throwable> refusals = new ArrayList<>();
        List<Map<String, String>> seenParams = new ArrayList<>();
        AtomicReference<RouteMatch> filterMatch = new AtomicReference<>();
        registry.registerFilter(Fixtures.filter("see", AdmissionPhase.AUTHENTICATION, match -> {
            filterMatch.set(match);
            try {
                match.pathParams().put("evil", "1");
            } catch (UnsupportedOperationException expected) {
                refusals.add(expected);
            }
        }));
        HttpHandler handler = ex -> {
            seenParams.add(Map.copyOf(ex.pathParams()));
            for (Runnable attack : List.<Runnable>of(
                    () -> ex.pathParams().put("evil", "1"),
                    () -> ex.pathParams().clear(),
                    () -> ex.pathParams().remove("x"),
                    () -> ex.pathParams().replaceAll((k, v) -> "evil"),
                    () -> filterMatch.get().pathParams().put("evil", "2"),
                    () -> filterMatch.get().route().requirements().clear(),
                    () -> filterMatch.get().route().requirements().add(RouteRequirement.permitAll()),
                    () -> filterMatch.get().route().paramNames().add("evil"))) {
                try {
                    attack.run();
                } catch (UnsupportedOperationException expected) {
                    refusals.add(expected);
                }
            }
            ex.respond(HttpStatus.OK);
        };
        registry.registerRoute(Fixtures.route(HttpMethod.GET, "/health",
                List.of(RouteRequirement.permitAll()), handler));
        for (int i = 0; i < 3; i++) {
            ScriptedExchange exchange = ScriptedExchange.get("/health");
            registry.handle(exchange);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        }
        assertThat(refusals).hasSize(3 * 9);
        assertThat(seenParams).hasSize(3).allSatisfy(params -> assertThat(params).isEmpty());
        assertThat(registry.matchRoute(HttpMethod.GET, "/health").orElseThrow().pathParams()).isEmpty();
        assertThat(registry.routes().get(0).requirements()).containsExactly(RouteRequirement.permitAll());
        assertThat(registry.routes().get(0).paramNames()).isEmpty();
    }

    @Test
    @DisplayName("a route re-registered after unregistration is matched as the new route object, and the old handler is unreachable")
    void reRegisteredRouteGetsItsOwnMatch() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<String> ran = new ArrayList<>();
        ApiSurfaceRoute oldRoute = Fixtures.open("/health", ex -> {
            ran.add("old");
            ex.respond(HttpStatus.OK);
        });
        ApiSurfaceRoute newRoute = Fixtures.open("/health", ex -> {
            ran.add("new");
            ex.respond(HttpStatus.ACCEPTED);
        });
        assertThat(newRoute).isEqualTo(oldRoute);
        registry.registerRoute(oldRoute);
        RouteMatch oldMatch = registry.matchRoute(HttpMethod.GET, "/health").orElseThrow();

        assertThatThrownBy(() -> registry.registerRoute(newRoute)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.unregisterRoute(newRoute)).as("an equal route with another handler is not the registered one")
                .isFalse();
        assertThat(registry.matchRoute(HttpMethod.GET, "/health").orElseThrow().route()).isSameAs(oldRoute);

        assertThat(registry.unregisterRoute(oldRoute)).isTrue();
        assertThat(registry.matchRoute(HttpMethod.GET, "/health")).isEmpty();
        registry.registerRoute(newRoute);
        RouteMatch newMatch = registry.matchRoute(HttpMethod.GET, "/health").orElseThrow();
        assertThat(newMatch.route()).isSameAs(newRoute);
        assertThat(oldMatch.route()).isSameAs(oldRoute);

        ScriptedExchange exchange = ScriptedExchange.get("/health");
        registry.handle(exchange);
        assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
        assertThat(ran).containsExactly("new");
    }

    @Test
    @DisplayName("clear() followed by registration of another route under the same path serves only the new one")
    void clearThenReRegister() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<String> ran = new ArrayList<>();
        registry.registerRoute(Fixtures.open("/a", ex -> {
            ran.add("old");
            ex.respond(HttpStatus.OK);
        }));
        registry.clear();
        registry.registerRoute(Fixtures.open("/a", ex -> {
            ran.add("new");
            ex.respond(HttpStatus.OK);
        }));
        registry.handle(ScriptedExchange.get("/a"));
        assertThat(ran).containsExactly("new");
    }

    @Test
    @DisplayName("a parameterised route still gets its own parameters per request, unmodifiable and independent")
    void parameterisedRouteKeepsPerRequestParams() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<Map<String, String>> seen = new ArrayList<>();
        registry.registerRoute(Fixtures.open("/items/{id}", ex -> {
            seen.add(ex.pathParams());
            ex.respond(HttpStatus.OK);
        }));
        registry.handle(ScriptedExchange.get("/items/7"));
        registry.handle(ScriptedExchange.get("/items/8?x=1"));
        registry.handle(ScriptedExchange.get("/items/9/"));
        assertThat(seen).containsExactly(Map.of("id", "7"), Map.of("id", "8"), Map.of("id", "9"));
        assertThatThrownBy(() -> seen.get(0).put("id", "x")).isInstanceOf(UnsupportedOperationException.class);
        RouteMatch a = registry.matchRoute(HttpMethod.GET, "/items/1").orElseThrow();
        RouteMatch b = registry.matchRoute(HttpMethod.GET, "/items/2").orElseThrow();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.pathParams()).containsExactly(Map.entry("id", "1"));
        assertThatThrownBy(() -> a.pathParams().put("id", "z")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a literal route wins over a parameterised one whichever was registered first; the first parameterised route wins among them")
    void precedence() {
        for (boolean literalFirst : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            ApiSurfaceRoute literal = Fixtures.open("/items/new");
            ApiSurfaceRoute byId = Fixtures.open("/items/{id}");
            ApiSurfaceRoute byName = Fixtures.open("/{kind}/new");
            if (literalFirst) {
                registry.registerRoute(literal);
            }
            registry.registerRoute(byId);
            registry.registerRoute(byName);
            if (!literalFirst) {
                registry.registerRoute(literal);
            }
            assertThat(registry.matchRoute(HttpMethod.GET, "/items/new").orElseThrow().route()).isSameAs(literal);
            assertThat(registry.matchRoute(HttpMethod.GET, "/items/other").orElseThrow().route()).isSameAs(byId);
            assertThat(registry.matchRoute(HttpMethod.GET, "/foo/new").orElseThrow().route()).isSameAs(byName);
        }
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ApiSurfaceRoute first = Fixtures.open("/a/{x}/c");
        ApiSurfaceRoute second = Fixtures.open("/a/{y}/{z}");
        registry.registerRoute(first);
        registry.registerRoute(second);
        assertThat(registry.matchRoute(HttpMethod.GET, "/a/1/c").orElseThrow().route()).isSameAs(first);
        assertThat(registry.matchRoute(HttpMethod.GET, "/a/1/d").orElseThrow().route()).isSameAs(second);
    }

    @Test
    @DisplayName("racing requests on one literal route all see the same route and an empty parameter map while one tries to write")
    void racingRequests() throws Exception {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<String> problems = new CopyOnWriteArrayList<>();
        registry.registerRoute(Fixtures.open("/r", ex -> {
            if (!ex.pathParams().isEmpty()) {
                problems.add("params " + ex.pathParams());
            }
            try {
                ex.pathParams().put("x", "y");
                problems.add("write accepted");
            } catch (UnsupportedOperationException expected) {
                // required
            }
            ex.respond(HttpStatus.OK);
        }));
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 5000; i++) {
                        ScriptedExchange exchange = ScriptedExchange.get("/r");
                        registry.handle(exchange);
                        if (!exchange.statuses().equals(List.of(HttpStatus.OK))) {
                            problems.add("status " + exchange.statuses());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(problems).isEmpty();
    }

    // ---- requirements walked by index ----------------------------------------------------------------------------

    private static HttpStatus serve(List<RouteRequirement> requirements, Fixtures.Person principal) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry(false);
        if (principal != null) {
            registry.registerFilter(new AdmissionFilterAuthenticating(principal));
        }
        registry.registerRoute(Fixtures.route(HttpMethod.GET, "/r", requirements, ex -> ex.respond(HttpStatus.OK)));
        ScriptedExchange exchange = ScriptedExchange.get("/r");
        registry.handle(exchange);
        return exchange.statuses().get(0);
    }

    private static final class AdmissionFilterAuthenticating implements eu.exeris.caps.serviceboundarycore.api.AdmissionFilter {
        private final Fixtures.Person principal;

        AdmissionFilterAuthenticating(Fixtures.Person principal) {
            this.principal = principal;
        }

        @Override public String name() { return "auth"; }

        @Override public AdmissionPhase phase() { return AdmissionPhase.AUTHENTICATION; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            eu.exeris.caps.serviceboundarycore.api.AdmissionContext.current().authenticate(principal);
            return true;
        }
    }

    @Test
    @DisplayName("requirements are conjunctive in order: the first denial wins, permitAll adds nothing, an empty list serves in lenient mode")
    void requirementsByIndex() {
        RouteRequirement authenticated = RouteRequirement.authenticated();
        RouteRequirement any = RouteRequirement.requiringAnyScope(java.util.Set.of("read", "write"));
        RouteRequirement all = RouteRequirement.requiringAllScopes(java.util.Set.of("read", "write"));
        RouteRequirement permit = RouteRequirement.permitAll();
        Fixtures.Person reader = Fixtures.person(1, "read");
        Fixtures.Person both = Fixtures.person(2, "read", "write");

        assertThat(serve(List.of(), null)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(), reader)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit), null)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit, permit, permit), null)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit, authenticated), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(serve(List.of(authenticated, permit), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(serve(List.of(permit, authenticated, permit), reader)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit, any, permit), reader)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit, all, permit), reader)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(serve(List.of(permit, all), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        // the first requirement that is not met decides the status, not the last and not the worst
        assertThat(serve(List.of(all, authenticated), reader)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(serve(List.of(authenticated, all), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(serve(List.of(any, all), reader)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(serve(List.of(any, all), both)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(permit, permit, permit, all), reader)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(serve(List.of(permit, permit, permit, all), both)).isEqualTo(HttpStatus.OK);
        assertThat(serve(List.of(RouteRequirement.requiringAnyScope(java.util.Set.of("none")), authenticated), reader))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
