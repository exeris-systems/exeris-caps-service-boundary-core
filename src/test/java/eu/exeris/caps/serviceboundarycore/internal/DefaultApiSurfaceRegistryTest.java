package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultApiSurfaceRegistry")
class DefaultApiSurfaceRegistryTest {

    private DefaultApiSurfaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
    }

    @Nested
    @DisplayName("route registration and lookup")
    class RouteRegistration {

        @Test
        @DisplayName("registers and matches an exact route")
        void matchesExactRoute() {
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/health")
                    .actionName("Health#check")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build();

            registry.registerRoute(route);

            assertThat(registry.routeCount()).isEqualTo(1);
            assertThat(registry.routes()).containsExactly(route);

            Optional<RouteMatch> match = registry.matchRoute(HttpMethod.GET, "/api/v1/health");
            assertThat(match).isPresent();
            assertThat(match.get().route()).isEqualTo(route);
            assertThat(match.get().pathParams()).isEmpty();
        }

        @Test
        @DisplayName("registers and matches a parameterized route with parameter extraction")
        void matchesParameterizedRoute() {
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/orders/{orderId}/items/{itemId}")
                    .actionName("Order#getItem")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build();

            registry.registerRoute(route);

            Optional<RouteMatch> match = registry.matchRoute(HttpMethod.GET, "/api/v1/orders/ord-123/items/item-456");
            assertThat(match).isPresent();
            assertThat(match.get().route()).isEqualTo(route);
            assertThat(match.get().pathParams())
                    .containsEntry("orderId", "ord-123")
                    .containsEntry("itemId", "item-456");
        }

        @Test
        @DisplayName("exact match takes precedence over parameterized route")
        void exactMatchPrecedence() {
            ApiSurfaceRoute parameterized = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/users/{id}")
                    .actionName("User#get")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build();

            ApiSurfaceRoute exact = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/users/me")
                    .actionName("User#me")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build();

            registry.registerRoute(parameterized);
            registry.registerRoute(exact);

            Optional<RouteMatch> match = registry.matchRoute(HttpMethod.GET, "/api/v1/users/me");
            assertThat(match).isPresent();
            assertThat(match.get().route()).isEqualTo(exact);
            assertThat(match.get().pathParams()).isEmpty();
        }

        @Test
        @DisplayName("unregisters an existing route")
        void unregistersRoute() {
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/ping")
                    .actionName("Ping#ping")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build();

            registry.registerRoute(route);
            assertThat(registry.routeCount()).isEqualTo(1);

            boolean removed = registry.unregisterRoute(route);
            assertThat(removed).isTrue();
            assertThat(registry.routeCount()).isZero();
            assertThat(registry.matchRoute(HttpMethod.GET, "/api/v1/ping")).isEmpty();
        }

        @Test
        @DisplayName("clears all routes")
        void clearsAllRoutes() {
            registry.registerRoute(ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/r1")
                    .actionName("A#1")
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build());
            registry.registerRoute(ApiSurfaceRoute.builder()
                    .method(HttpMethod.POST)
                    .pathPattern("/r2")
                    .actionName("A#2")
                    .handler(ex -> ex.respond(HttpStatus.CREATED))
                    .build());

            assertThat(registry.routeCount()).isEqualTo(2);
            registry.clear();
            assertThat(registry.routeCount()).isZero();
            assertThat(registry.routes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("HTTP exchange dispatching")
    class HttpDispatch {

        @Test
        @DisplayName("dispatches to matched route and injects path parameters into exchange")
        void dispatchesToMatchedRoute() {
            AtomicReference<Map<String, String>> receivedParams = new AtomicReference<>();
            AtomicBoolean handlerInvoked = new AtomicBoolean();

            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/customers/{cid}/invoices/{invId}")
                    .actionName("Invoice#get")
                    .handler(ex -> {
                        handlerInvoked.set(true);
                        receivedParams.set(ex.pathParams());
                        ex.respond(HttpStatus.OK);
                    })
                    .build();

            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/customers/CUST-1/invoices/INV-99", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(handlerInvoked).isTrue();
            assertThat(receivedParams.get())
                    .containsEntry("cid", "CUST-1")
                    .containsEntry("invId", "INV-99");
            assertThat(exchange.status).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("returns 404 NOT_FOUND when no route matches path")
        void returnsNotFoundForUnmappedPath() {
            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/nonexistent/path", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(exchange.status).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 405 METHOD_NOT_ALLOWED when path matches under another method")
        void returnsMethodNotAllowed() {
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.POST)
                    .pathPattern("/api/v1/orders")
                    .actionName("Order#create")
                    .handler(ex -> ex.respond(HttpStatus.CREATED))
                    .build();

            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/api/v1/orders", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(exchange.status).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(exchange.response).isNotNull();
            assertThat(exchange.response.headers())
                    .anyMatch(h -> h.name().equalsIgnoreCase("Allow") && h.value().contains("POST"));
        }

        @Test
        @DisplayName("returns 500 INTERNAL_SERVER_ERROR when handler throws uncaught exception")
        void returnsInternalServerErrorOnException() {
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/faulty")
                    .actionName("Faulty#run")
                    .handler(ex -> {
                        throw new RuntimeException("Simulated boundary fault");
                    })
                    .build();

            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/faulty", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(exchange.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("strips query string before matching route")
        void stripsQueryString() {
            AtomicBoolean invoked = new AtomicBoolean();
            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/v1/search")
                    .actionName("Search#query")
                    .handler(ex -> {
                        invoked.set(true);
                        ex.respond(HttpStatus.OK);
                    })
                    .build();

            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/api/v1/search?q=test&limit=10", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(invoked).isTrue();
            assertThat(exchange.status).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("registration guards")
    class RegistrationGuards {

        private ApiSurfaceRoute route(String action, HttpHandler handler) {
            return ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern("/dup")
                    .actionName(action).handler(handler).build();
        }

        private eu.exeris.caps.serviceboundarycore.api.AdmissionFilter named(String name) {
            return new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return name; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) { return true; }
            };
        }

        @Test
        @DisplayName("patterns that differ only in parameter names are one route and the second is refused")
        void aliasedParameterRouteRefused() {
            HttpHandler first = ex -> { };
            registry.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern("/a/{id}")
                    .actionName("A#a").handler(first).build());

            assertThatThrownBy(() -> registry.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.GET)
                    .pathPattern("/a/{name}").actionName("B#b").handler(ex -> { }).build()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(registry.routeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same parameterised shape is allowed for another method or another literal segment")
        void distinctShapesAllowed() {
            registry.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern("/a/{id}")
                    .actionName("A#a").handler(ex -> { }).build());
            registry.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.POST).pathPattern("/a/{name}")
                    .actionName("B#b").handler(ex -> { }).build());
            registry.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern("/b/{id}")
                    .actionName("C#c").handler(ex -> { }).build());

            assertThat(registry.routeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a second route for the same method and pattern is refused")
        void duplicateRouteRefused() {
            registry.registerRoute(route("A#a", ex -> { }));

            assertThatThrownBy(() -> registry.registerRoute(route("B#b", ex -> { })))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(registry.routeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("unregistering an equal route with another handler removes nothing")
        void unregisterNeedsSameHandler() {
            registry.registerRoute(route("A#a", ex -> { }));

            assertThat(registry.unregisterRoute(route("A#a", ex -> { }))).isFalse();
            assertThat(registry.routeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a filter with a null, blank or duplicate name is refused")
        void filterNameGuards() {
            registry.registerFilter(named("one"));

            assertThatThrownBy(() -> registry.registerFilter(named(null))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.registerFilter(named(" "))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.registerFilter(named("one"))).isInstanceOf(IllegalStateException.class);
            assertThat(registry.filters()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("admission filter chain ordering and execution (ADR-024, ADR-099)")
    class FilterChainExecution {

        @Test
        @DisplayName("executes filters strictly by phase then lexicographically by name")
        void executesFiltersByPhaseAndName() {
            List<String> executionOrder = new ArrayList<>();

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter authFilter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "jwt-auth"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.AUTHENTICATION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("AUTHENTICATION:jwt-auth");
                    return true;
                }
            };

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter rateLimitFilter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "ip-rate-limit"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.RATE_LIMITING; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("RATE_LIMITING:ip-rate-limit");
                    return true;
                }
            };

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter preFilterB = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "b-envelope"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("PRE_ADMISSION:b-envelope");
                    return true;
                }
            };

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter preFilterA = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "a-headers"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("PRE_ADMISSION:a-headers");
                    return true;
                }
            };

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter rbacFilter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "rbac-policy"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.AUTHORIZATION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("AUTHORIZATION:rbac-policy");
                    return true;
                }
            };

            // Register in arbitrary out-of-order sequence
            registry.registerFilter(rbacFilter);
            registry.registerFilter(authFilter);
            registry.registerFilter(preFilterB);
            registry.registerFilter(rateLimitFilter);
            registry.registerFilter(preFilterA);

            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/test")
                    .actionName("Test#action")
                    .handler(ex -> {
                        executionOrder.add("HANDLER");
                        ex.respond(HttpStatus.OK);
                    })
                    .build();
            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(executionOrder).containsExactly(
                    "PRE_ADMISSION:a-headers",
                    "PRE_ADMISSION:b-envelope",
                    "RATE_LIMITING:ip-rate-limit",
                    "AUTHENTICATION:jwt-auth",
                    "AUTHORIZATION:rbac-policy",
                    "HANDLER"
            );
            assertThat(exchange.status).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("filter returning false halts chain and handler execution")
        void filterShortCircuits() {
            List<String> executionOrder = new ArrayList<>();

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter rateLimiter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "rate-limit"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.RATE_LIMITING; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("RATE_LIMITING:blocked");
                    ex.respond(HttpStatus.TOO_MANY_REQUESTS);
                    return false;
                }
            };

            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter authFilter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "jwt-auth"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.AUTHENTICATION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    executionOrder.add("AUTHENTICATION:should_not_run");
                    return true;
                }
            };

            registry.registerFilter(rateLimiter);
            registry.registerFilter(authFilter);

            ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/test")
                    .actionName("Test#action")
                    .handler(ex -> {
                        executionOrder.add("HANDLER:should_not_run");
                        ex.respond(HttpStatus.OK);
                    })
                    .build();
            registry.registerRoute(route);

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(executionOrder).containsExactly("RATE_LIMITING:blocked");
            assertThat(exchange.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("filter that responds and then throws yields exactly one response")
        void filterRespondsThenThrows() {
            registry.registerFilter(new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "responds-then-fails"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) {
                    ex.respond(HttpStatus.FORBIDDEN);
                    throw new IllegalStateException("boom");
                }
            });
            AtomicBoolean invoked = new AtomicBoolean();
            registry.registerRoute(ApiSurfaceRoute.builder()
                    .method(HttpMethod.GET)
                    .pathPattern("/api/test")
                    .actionName("Test#action")
                    .handler(ex -> invoked.set(true))
                    .build());

            RecordingHttpExchange exchange = new RecordingHttpExchange(
                    HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
            );

            registry.handle(exchange);

            assertThat(exchange.responses).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(invoked).isFalse();
        }

        @Test
        @DisplayName("unregisters filter cleanly")
        void unregistersFilter() {
            eu.exeris.caps.serviceboundarycore.api.AdmissionFilter filter = new eu.exeris.caps.serviceboundarycore.api.AdmissionFilter() {
                @Override public String name() { return "temp-filter"; }
                @Override public eu.exeris.caps.serviceboundarycore.api.AdmissionPhase phase() { return eu.exeris.caps.serviceboundarycore.api.AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange ex, RouteMatch rm) { return true; }
            };

            registry.registerFilter(filter);
            assertThat(registry.filters()).contains(filter);

            boolean removed = registry.unregisterFilter(filter);
            assertThat(removed).isTrue();
            assertThat(registry.filters()).doesNotContain(filter);
        }
    }

    private static class RecordingHttpExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse response;
        private HttpStatus status;
        private Object body;
        private final List<HttpStatus> responses = new ArrayList<>();
        private Map<String, String> pathParams = Map.of();

        RecordingHttpExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Map<String, String> pathParams() {
            return pathParams;
        }

        @Override
        public void respond(HttpResponse response) {
            this.response = response;
            this.status = response.status();
            responses.add(status);
        }

        @Override
        public void respond(HttpTypedResponse typedResponse) {
            this.status = typedResponse.status();
            responses.add(status);
        }

        @Override
        public void respond(HttpStatus status, Object body) {
            this.status = status;
            this.body = body;
            responses.add(status);
        }

        @Override
        public void respond(HttpStatus status) {
            this.status = status;
            responses.add(status);
        }
    }
}
