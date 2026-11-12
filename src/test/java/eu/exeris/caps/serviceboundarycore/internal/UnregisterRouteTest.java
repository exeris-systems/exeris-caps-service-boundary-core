package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.CountingHandler;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("unregisterRoute removes a route that equals the given one and has the same handler instance")
class UnregisterRouteTest {

    private static final List<RouteRequirement> OPEN = List.of(RouteRequirement.permitAll());

    private DefaultApiSurfaceRegistry registry;
    private CountingHandler handler;
    private CountingHandler otherHandler;
    private ApiSurfaceRoute route;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        handler = new CountingHandler();
        otherHandler = new CountingHandler();
        route = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#get", OPEN, handler);
        registry.registerRoute(route);
    }

    private HttpStatus status(HttpMethod method, String path) {
        ScriptedExchange exchange = ScriptedExchange.of(method, path, List.of());
        registry.handle(exchange);
        return exchange.statuses().get(0);
    }

    private void assertNoHandlerMethodWasCalled() {
        assertThat(handler.objectMethodCalls()).as("equals, hashCode, toString of the registered handler").isZero();
        assertThat(otherHandler.objectMethodCalls()).as("equals, hashCode, toString of the other handler").isZero();
    }

    @Test
    @DisplayName("the same route object is removed, and its requests are no longer served")
    void sameObject() {
        assertThat(registry.unregisterRoute(route)).isTrue();

        assertThat(registry.routeCount()).isZero();
        assertThat(status(HttpMethod.GET, "/items/7")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleCalls.get()).isZero();
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("an equal route object with the same handler instance is removed")
    void equalRouteSameHandler() {
        ApiSurfaceRoute copy = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#get", OPEN, handler);

        assertThat(copy).isNotSameAs(route);
        assertThat(registry.unregisterRoute(copy)).isTrue();

        assertThat(registry.routes()).isEmpty();
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("an equal route with the same handler but other requirements is removed: requirements are not part of equality")
    void requirementsAreNotCompared() {
        ApiSurfaceRoute copy = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#get", List.of(), handler);

        assertThat(registry.unregisterRoute(copy)).isTrue();

        assertThat(registry.routes()).isEmpty();
    }

    @Test
    @DisplayName("a path written with a trailing slash is the same pattern, so that route is removed too")
    void normalisedPathIsTheSamePattern() {
        ApiSurfaceRoute copy = new ApiSurfaceRoute(HttpMethod.GET, "items/{id}/", "Items#get", OPEN, handler);

        assertThat(registry.unregisterRoute(copy)).isTrue();

        assertThat(registry.routes()).isEmpty();
    }

    @Test
    @DisplayName("an equal route with a different handler instance removes nothing, and the registered route still serves")
    void equalRouteDifferentHandler() {
        ApiSurfaceRoute copy = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#get", OPEN, otherHandler);

        assertThat(copy).isEqualTo(route);
        assertThat(registry.unregisterRoute(copy)).isFalse();

        assertThat(registry.routeCount()).isEqualTo(1);
        assertThat(registry.routes().get(0).handler()).isSameAs(handler);
        assertThat(status(HttpMethod.GET, "/items/7")).isEqualTo(HttpStatus.OK);
        assertThat(handler.handleCalls.get()).isEqualTo(1);
        assertThat(otherHandler.handleCalls.get()).isZero();
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("a route that differs only in its action name removes nothing")
    void differentActionName() {
        ApiSurfaceRoute renamed = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#other", OPEN, handler);

        assertThat(registry.unregisterRoute(renamed)).isFalse();

        assertThat(registry.routes()).containsExactly(route);
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("a route that differs only in its method or its path pattern removes nothing")
    void differentMethodOrPath() {
        ApiSurfaceRoute otherMethod = new ApiSurfaceRoute(HttpMethod.PUT, "/items/{id}", "Items#get", OPEN, handler);
        ApiSurfaceRoute otherPath = new ApiSurfaceRoute(HttpMethod.GET, "/items/{key}", "Items#get", OPEN, handler);

        assertThat(registry.unregisterRoute(otherMethod)).isFalse();
        assertThat(registry.unregisterRoute(otherPath)).isFalse();

        assertThat(registry.routes()).containsExactly(route);
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("with several routes, only the one that equals the given route and shares its handler goes")
    void onlyTheMatchingRouteGoes() {
        ApiSurfaceRoute second = new ApiSurfaceRoute(HttpMethod.GET, "/orders", "Orders#list", OPEN, handler);
        ApiSurfaceRoute third = new ApiSurfaceRoute(HttpMethod.POST, "/orders", "Orders#create", OPEN, otherHandler);
        registry.registerRoute(second);
        registry.registerRoute(third);

        assertThat(registry.unregisterRoute(new ApiSurfaceRoute(HttpMethod.GET, "/orders", "Orders#list", OPEN, handler)))
                .isTrue();

        assertThat(registry.routes()).containsExactly(route, third);
        assertThat(registry.unregisterRoute(second)).as("already gone").isFalse();
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("while frozen the removal is refused and the route stays; during teardown it is permitted")
    void phases() {
        registry.freeze();
        assertThatThrownBy(() -> registry.unregisterRoute(route)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The registry is frozen and can no longer be modified");
        assertThat(registry.routes()).containsExactly(route);

        registry.beginTeardown();
        ApiSurfaceRoute differentHandler = new ApiSurfaceRoute(HttpMethod.GET, "/items/{id}", "Items#get", OPEN, otherHandler);
        assertThat(registry.unregisterRoute(differentHandler)).isFalse();
        assertThat(registry.unregisterRoute(route)).isTrue();
        assertThat(registry.routes()).isEmpty();
        assertNoHandlerMethodWasCalled();
    }

    @Test
    @DisplayName("a null route is refused with a NullPointerException naming the argument")
    void nullRoute() {
        assertThatThrownBy(() -> registry.unregisterRoute(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("route");
        assertThat(registry.routes()).containsExactly(route);
    }
}
