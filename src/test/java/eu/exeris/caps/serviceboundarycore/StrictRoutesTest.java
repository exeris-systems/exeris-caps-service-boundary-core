package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Strict routes by default")
class StrictRoutesTest {

    private static final String HANDLER_DETAIL = "SecretHandlerDetail";

    /** The three ways to obtain a default registry, each named for the parameterised display. */
    record Path(String name, Supplier<ApiSurfaceRegistry> registry) {
        @Override public String toString() { return name; }
    }

    static Stream<Path> defaultPaths() {
        return Stream.of(
                new Path("new DefaultApiSurfaceRegistry()", DefaultApiSurfaceRegistry::new),
                new Path("new ServiceBoundaryCoreModule()", () -> new ServiceBoundaryCoreModule().apiSurfaceRegistry()),
                new Path("builder().build()", () -> ServiceBoundaryCoreModule.builder().build().apiSurfaceRegistry()));
    }

    static Stream<Path> optOutPaths() {
        return Stream.of(
                new Path("new DefaultApiSurfaceRegistry(false)", () -> new DefaultApiSurfaceRegistry(false)),
                new Path("builder().strictRoutes(false).build()",
                        () -> ServiceBoundaryCoreModule.builder().strictRoutes(false).build().apiSurfaceRegistry()));
    }

    private static ApiSurfaceRoute route(String path, List<RouteRequirement> requirements, HttpHandler handler) {
        return new ApiSurfaceRoute(HttpMethod.GET, path, "Action#" + HANDLER_DETAIL, requirements, handler);
    }

    private static HttpHandler ok() {
        return ex -> ex.respond(HttpStatus.OK);
    }

    private static HttpStatus serve(ApiSurfaceRegistry registry, String path) {
        ScriptedExchange exchange = ScriptedExchange.get(path);
        registry.handle(exchange);
        assertThat(exchange.respondCount()).isEqualTo(1);
        return exchange.statuses().get(0);
    }

    @ParameterizedTest(name = "{0} refuses an empty requirement list")
    @MethodSource("defaultPaths")
    @DisplayName("an empty requirement list is refused at registration")
    void emptyListRefused(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();

        assertThatThrownBy(() -> registry.registerRoute(route("/open", List.of(), ok())))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET")
                .hasMessageContaining("/open")
                .hasMessageNotContaining(HANDLER_DETAIL)
                .hasMessageNotContaining("Lambda")
                .hasMessageNotContaining("$$");
        assertThat(registry.routeCount()).isZero();
        assertThat(serve(registry, "/open")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest(name = "{0} refuses a null requirement list")
    @MethodSource("defaultPaths")
    @DisplayName("a null requirement list is refused at registration")
    void nullListRefused(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();

        assertThatThrownBy(() -> registry.registerRoute(route("/open", null, ok())))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/open")
                .hasMessageNotContaining(HANDLER_DETAIL);
        assertThat(registry.routeCount()).isZero();
    }

    @ParameterizedTest(name = "{0} refuses a route built without calling requirements(...)")
    @MethodSource("defaultPaths")
    @DisplayName("a builder route that never set requirements is refused")
    void builderDefaultRefused(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();
        ApiSurfaceRoute bare = ApiSurfaceRoute.builder().pathPattern("/bare").handler(ok()).build();

        assertThatThrownBy(() -> registry.registerRoute(bare)).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(registry.routeCount()).isZero();
    }

    @ParameterizedTest(name = "{0} reports strict")
    @MethodSource("defaultPaths")
    @DisplayName("the default registry reports strict routes")
    void defaultReportsStrict(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();
        assertThat(registry).isInstanceOf(DefaultApiSurfaceRegistry.class);
        assertThat(((DefaultApiSurfaceRegistry) registry).isStrictRoutes()).isTrue();
    }

    @Test
    @DisplayName("isStrictRoutes() is true for new ServiceBoundaryCoreModule() and builder().build()")
    void moduleReportsStrictByDefault() {
        assertThat(new ServiceBoundaryCoreModule().isStrictRoutes()).isTrue();
        assertThat(ServiceBoundaryCoreModule.builder().build().isStrictRoutes()).isTrue();
        assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(true).build().isStrictRoutes()).isTrue();
        assertThat(new DefaultApiSurfaceRegistry().isStrictRoutes()).isTrue();
        assertThat(new DefaultApiSurfaceRegistry(true).isStrictRoutes()).isTrue();
    }

    @ParameterizedTest(name = "{0} accepts permitAll and serves an unauthenticated request")
    @MethodSource("defaultPaths")
    @DisplayName("permitAll declares an anonymous route that is served to a request no filter authenticated")
    void permitAllServedAnonymously(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();
        registry.registerRoute(route("/open", List.of(RouteRequirement.permitAll()), ok()));

        assertThat(registry.routeCount()).isEqualTo(1);
        assertThat(serve(registry, "/open")).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest(name = "{0} still enforces a real requirement")
    @MethodSource("defaultPaths")
    @DisplayName("a route with a real requirement behaves as before: 401 anonymous, 403 without scope, served with it")
    void realRequirementUnchanged(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();
        java.util.concurrent.atomic.AtomicReference<Probes.Principal> who = new java.util.concurrent.atomic.AtomicReference<>();
        registry.registerFilter(Probes.filter("auth", (ex, m) -> {
            Probes.Principal principal = who.get();
            if (principal != null) {
                AdmissionContext.current().authenticate(principal);
            }
            return true;
        }));
        registry.registerRoute(route("/authenticated", List.of(RouteRequirement.authenticated()), ok()));
        registry.registerRoute(route("/scoped", List.of(RouteRequirement.requiringAllScopes(java.util.Set.of("w"))), ok()));

        assertThat(serve(registry, "/authenticated")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(serve(registry, "/scoped")).isEqualTo(HttpStatus.UNAUTHORIZED);
        who.set(new Probes.Principal(java.util.Set.of("r")));
        assertThat(serve(registry, "/authenticated")).isEqualTo(HttpStatus.OK);
        assertThat(serve(registry, "/scoped")).isEqualTo(HttpStatus.FORBIDDEN);
        who.set(new Probes.Principal(java.util.Set.of("w")));
        assertThat(serve(registry, "/scoped")).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest(name = "{0} accepts an empty and a null list and serves them anonymously")
    @MethodSource("optOutPaths")
    @DisplayName("strictRoutes(false) and new DefaultApiSurfaceRegistry(false) accept a route without a requirement")
    void optOutAcceptsAndServesAnonymously(Path path) {
        ApiSurfaceRegistry registry = path.registry().get();

        registry.registerRoute(route("/empty", List.of(), ok()));
        registry.registerRoute(route("/null", null, ok()));

        assertThat(registry.routeCount()).isEqualTo(2);
        assertThat(((DefaultApiSurfaceRegistry) registry).isStrictRoutes()).isFalse();
        assertThat(serve(registry, "/empty")).isEqualTo(HttpStatus.OK);
        assertThat(serve(registry, "/null")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the opt-out is reported by the module")
    void optOutReportedByModule() {
        assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(false).build().isStrictRoutes()).isFalse();
    }

    @Test
    @DisplayName("an opted-out registry still enforces a real requirement")
    void optOutStillEnforcesRealRequirement() {
        ApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry(false);
        registry.registerRoute(route("/authenticated", List.of(RouteRequirement.authenticated()), ok()));
        assertThat(serve(registry, "/authenticated")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the last strictRoutes call on the builder wins")
    void lastStrictRoutesCallWins() {
        assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(false).strictRoutes(true).build().isStrictRoutes())
                .isTrue();
        assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(true).strictRoutes(false).build().isStrictRoutes())
                .isFalse();
    }

    // ---- custom registry ------------------------------------------------------------------------------------

    /** A registry that is not the default one. */
    private static final class CustomRegistry implements ApiSurfaceRegistry {
        private final List<ApiSurfaceRoute> routes = new ArrayList<>();

        @Override public void registerRoute(ApiSurfaceRoute route) { routes.add(route); }
        @Override public boolean unregisterRoute(ApiSurfaceRoute route) { return routes.remove(route); }
        @Override public Optional<RouteMatch> matchRoute(HttpMethod method, String path) { return Optional.empty(); }
        @Override public List<ApiSurfaceRoute> routes() { return List.copyOf(routes); }
        @Override public int routeCount() { return routes.size(); }
        @Override public void clear() { routes.clear(); }
        @Override public void registerFilter(AdmissionFilter filter) { }
        @Override public boolean unregisterFilter(AdmissionFilter filter) { return false; }
        @Override public List<AdmissionFilter> filters() { return List.of(); }
        @Override public RequestFilterChain filterChain() { return RequestFilterChain.empty(); }
        @Override public void handle(HttpExchange exchange) { }
    }

    @Test
    @DisplayName("a custom registry combined with strictRoutes(true) is refused at build(), in either call order")
    void customRegistryWithStrictRefused() {
        CustomRegistry custom = new CustomRegistry();

        assertThatThrownBy(() -> ServiceBoundaryCoreModule.builder().registry(custom).strictRoutes(true).build())
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ServiceBoundaryCoreModule.builder().strictRoutes(true).registry(custom).build())
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a custom registry with no strictRoutes call builds, is the registry served, and is not strict")
    void customRegistryWithoutStrictBuilds() {
        CustomRegistry custom = new CustomRegistry();

        ServiceBoundaryCoreModule module = ServiceBoundaryCoreModule.builder().registry(custom).build();

        assertThat(module.apiSurfaceRegistry()).isSameAs(custom);
        assertThat(module.isStrictRoutes()).isFalse();
        module.apiSurfaceRegistry().registerRoute(route("/anything", List.of(), ok()));
        assertThat(custom.routeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a custom registry with strictRoutes(false) builds and is not strict")
    void customRegistryWithOptOutBuilds() {
        CustomRegistry custom = new CustomRegistry();

        ServiceBoundaryCoreModule module = ServiceBoundaryCoreModule.builder().registry(custom).strictRoutes(false).build();

        assertThat(module.apiSurfaceRegistry()).isSameAs(custom);
        assertThat(module.isStrictRoutes()).isFalse();
    }

    @Test
    @DisplayName("a lenient registry that already holds a route cannot be made strict through the builder")
    void lenientRegistryWithRouteCannotBecomeStrict() {
        DefaultApiSurfaceRegistry lenient = new DefaultApiSurfaceRegistry(false);
        lenient.registerRoute(route("/legacy", List.of(), ok()));

        assertThatThrownBy(() -> ServiceBoundaryCoreModule.builder().registry(lenient).strictRoutes(true).build())
                .isExactlyInstanceOf(IllegalStateException.class);
        ServiceBoundaryCoreModule viaConstructor = new ServiceBoundaryCoreModule(lenient,
                new eu.exeris.caps.serviceboundarycore.internal.CompositeServiceLifecycleHooks());
        assertThat(viaConstructor.isStrictRoutes()).isFalse();
    }

    @Test
    @DisplayName("a module built through the builder starts with no route, whichever strictRoutes decision it took")
    void builtModuleHoldsNoRoute() {
        assertThat(ServiceBoundaryCoreModule.builder().build().apiSurfaceRegistry().routeCount()).isZero();
        assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(false).build().apiSurfaceRegistry().routeCount())
                .isZero();
    }

    @Test
    @DisplayName("a strict DefaultApiSurfaceRegistry handed to the module constructor is reported strict")
    void explicitStrictDefaultRegistryReportedStrict() {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule(new DefaultApiSurfaceRegistry(),
                new eu.exeris.caps.serviceboundarycore.internal.CompositeServiceLifecycleHooks());
        assertThat(module.isStrictRoutes()).isTrue();
    }

    // ---- adversarial ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a requirement list with a null element is refused when the route is built, so it is never registered")
    void nullElementRefusedAtRouteConstruction() {
        List<RouteRequirement> withNull = Arrays.asList(RouteRequirement.authenticated(), null);

        assertThatThrownBy(() -> route("/x", withNull, ok())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ApiSurfaceRoute.builder().pathPattern("/x").handler(ok()).requirements(withNull))
                .isInstanceOf(NullPointerException.class);
        assertThat(new DefaultApiSurfaceRegistry().routeCount()).isZero();
    }

    @Test
    @DisplayName("a mutable requirement list emptied after registration does not unprotect the route")
    void listEmptiedAfterRegistrationKeepsRequirement() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<RouteRequirement> mutable = new ArrayList<>(List.of(RouteRequirement.authenticated()));
        ApiSurfaceRoute route = route("/guarded", mutable, ok());
        registry.registerRoute(route);

        mutable.clear();

        assertThat(route.requirements()).containsExactly(RouteRequirement.authenticated());
        assertThat(registry.routes().get(0).requirements()).hasSize(1);
        assertThat(serve(registry, "/guarded")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a list emptied between route construction and registration is judged on the copy taken at construction")
    void listEmptiedBeforeRegistrationKeepsRequirement() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<RouteRequirement> mutable = new ArrayList<>(List.of(RouteRequirement.authenticated()));
        ApiSurfaceRoute route = route("/guarded", mutable, ok());
        mutable.clear();

        registry.registerRoute(route);

        assertThat(serve(registry, "/guarded")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the requirement list a route exposes cannot be emptied")
    void exposedRequirementListIsImmutable() {
        ApiSurfaceRoute route = route("/guarded", List.of(RouteRequirement.authenticated()), ok());
        assertThatThrownBy(() -> route.requirements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("permitAll together with another requirement: the other requirement is enforced")
    void permitAllMixedWithAnotherRequirement() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        java.util.concurrent.atomic.AtomicReference<Probes.Principal> who = new java.util.concurrent.atomic.AtomicReference<>();
        registry.registerFilter(Probes.filter("auth", (ex, m) -> {
            if (who.get() != null) {
                AdmissionContext.current().authenticate(who.get());
            }
            return true;
        }));
        registry.registerRoute(route("/mixed",
                List.of(RouteRequirement.permitAll(), RouteRequirement.authenticated()), ok()));
        registry.registerRoute(route("/mixed-reversed",
                List.of(RouteRequirement.authenticated(), RouteRequirement.permitAll()), ok()));

        assertThat(serve(registry, "/mixed")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(serve(registry, "/mixed-reversed")).isEqualTo(HttpStatus.UNAUTHORIZED);
        who.set(new Probes.Principal());
        assertThat(serve(registry, "/mixed")).isEqualTo(HttpStatus.OK);
        assertThat(serve(registry, "/mixed-reversed")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("replacing a route while open: a duplicate without requirement is refused and the original is kept and served")
    void replacementWithoutRequirementRefusedOriginalKept() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ApiSurfaceRoute original = route("/guarded", List.of(RouteRequirement.authenticated()), ok());
        registry.registerRoute(original);

        assertThatThrownBy(() -> registry.registerRoute(route("/guarded", List.of(), ok())))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(registry.routes()).containsExactly(original);
        assertThat(serve(registry, "/guarded")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("replacing a route while open by unregistering it: the replacement without requirement is refused and the path stays unserved")
    void unregisterThenRegisterWithoutRequirementRefused() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ApiSurfaceRoute original = route("/guarded", List.of(RouteRequirement.authenticated()), ok());
        registry.registerRoute(original);
        assertThat(registry.unregisterRoute(original)).isTrue();

        assertThatThrownBy(() -> registry.registerRoute(route("/guarded", List.of(), ok())))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(registry.routeCount()).isZero();
        assertThat(serve(registry, "/guarded")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a refused route leaves the registry open for a correct registration")
    void refusalDoesNotPoisonRegistry() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        assertThatThrownBy(() -> registry.registerRoute(route("/a", List.of(), ok())))
                .isInstanceOf(IllegalStateException.class);

        registry.registerRoute(route("/a", List.of(RouteRequirement.permitAll()), ok()));

        assertThat(registry.routeCount()).isEqualTo(1);
        assertThat(serve(registry, "/a")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("strictness does not depend on the registry phase: a frozen registry still refuses with the frozen message first")
    void frozenRegistryRefusesAddition() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.freeze();

        assertThatThrownBy(() -> registry.registerRoute(route("/a", List.of(), ok())))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("The registry is frozen and can no longer be modified");
    }
}
