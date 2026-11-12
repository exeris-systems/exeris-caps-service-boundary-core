package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.internal.CompositeServiceLifecycleHooks;
import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sentences the README, the roadmap and the Javadoc state about behaviour, each decided by a test. */
@DisplayName("Documentation as contract: answered exchanges, answering failures, isStrictRoutes, permitAll")
class DocumentationStatementsTest {

    private static String normalised(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("\\s+", " ");
    }

    // ---- README sentences ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("README sentences")
    class Readme {

        @Test
        @DisplayName("admission ends when the exchange is answered, whatever the filter returned, with one WARNING for a true")
        void admissionEnds() throws IOException {
            String readme = normalised("README.md");
            assertThat(readme)
                    .contains("After each filter returns, the registry reads whether the exchange has been answered.")
                    .contains("the remaining filters and the handler do not run, whatever the filter returned")
                    .contains("a filter that answered and returned `true` is a contract violation and is logged once at WARNING")
                    .contains("the exchange throws `IllegalStateException` before writing anything, and the registry adds no guard of its own");
        }

        @Test
        @DisplayName("a failed 500 follows one rule: the original Error leaves with the failure suppressed, otherwise ERROR and a normal return")
        void answeringFailureRule() throws IOException {
            String readme = normalised("README.md");
            assertThat(readme)
                    .contains("If the original failure was an `Error`, that `Error` leaves `handle()` with the answering failure attached as suppressed.")
                    .contains("the answering failure is logged once at ERROR by class name and `handle()` returns normally")
                    .contains("A `VirtualMachineError` thrown by the answering attempt always propagates.");
        }

        @Test
        @DisplayName("the 500 is a bare status unlike the 405, which carries Allow")
        void bareStatusUnlike405() throws IOException {
            assertThat(normalised("README.md"))
                    .contains("a bare status with no body and no headers, unlike the 405 answer, which carries an `Allow` header");
        }

        @Test
        @DisplayName("a thrown Exception is logged without its throwable and message")
        void thrownExceptionRecord() throws IOException {
            assertThat(normalised("README.md"))
                    .contains("the exception's class name; the throwable and its message are not logged");
        }

        @Test
        @DisplayName("permitAll() mixed with another requirement adds nothing and still satisfies strict mode")
        void permitAllMixed() throws IOException {
            assertThat(normalised("README.md"))
                    .contains("mixed with another requirement it adds nothing: the other requirement is enforced and the route is not anonymous")
                    .contains("it satisfies strict route mode");
        }
    }

    // ---- ROADMAP ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ROADMAP states that the admission path allocates per request and carries no byte figure")
    void roadmapAllocationItem() throws IOException {
        String roadmap = Files.readString(Path.of("ROADMAP.md"));
        String heading = "### The admission path allocates on every request";
        int start = roadmap.indexOf(heading);
        assertThat(start).as("the allocation item exists").isGreaterThanOrEqualTo(0);
        int next = roadmap.indexOf("\n### ", start + heading.length());
        int nextTop = roadmap.indexOf("\n## ", start + heading.length());
        int end = roadmap.length();
        if (next >= 0) {
            end = Math.min(end, next);
        }
        if (nextTop >= 0) {
            end = Math.min(end, nextTop);
        }
        String section = roadmap.substring(start, end);

        assertThat(section).contains("allocates").contains("every request");
        assertThat(Pattern.compile("(?i)\\d[\\d.,_]*\\s*-?\\s*(bytes?|kib|kb|mib|mb|gib|gb|b)\\b").matcher(section).find())
                .as("no number followed by a byte unit in the allocation item")
                .isFalse();
    }

    // ---- isStrictRoutes per construction path -------------------------------------------------------------------

    /** A registry that is not the default one; its route policy is not known to the module. */
    private static final class OtherRegistry implements ApiSurfaceRegistry {
        @Override public void registerRoute(ApiSurfaceRoute route) { }
        @Override public boolean unregisterRoute(ApiSurfaceRoute route) { return false; }
        @Override public Optional<RouteMatch> matchRoute(HttpMethod method, String path) { return Optional.empty(); }
        @Override public List<ApiSurfaceRoute> routes() { return List.of(); }
        @Override public int routeCount() { return 0; }
        @Override public void clear() { }
        @Override public void registerFilter(AdmissionFilter filter) { }
        @Override public boolean unregisterFilter(AdmissionFilter filter) { return false; }
        @Override public List<AdmissionFilter> filters() { return List.of(); }
        @Override public RequestFilterChain filterChain() { return RequestFilterChain.empty(); }
        @Override public void handle(HttpExchange exchange) { }
    }

    private static ServiceBoundaryCoreModule viaConstructor(ApiSurfaceRegistry registry) {
        return new ServiceBoundaryCoreModule(registry, new CompositeServiceLifecycleHooks());
    }

    @Nested
    @DisplayName("ServiceBoundaryCoreModule.isStrictRoutes() for every construction path in its Javadoc")
    class IsStrictRoutes {

        @Test
        @DisplayName("new ServiceBoundaryCoreModule(): true")
        void noArgConstructor() {
            assertThat(new ServiceBoundaryCoreModule().isStrictRoutes()).isTrue();
        }

        @Test
        @DisplayName("a builder that sets neither a registry nor strictRoutes: true")
        void bareBuilder() {
            assertThat(ServiceBoundaryCoreModule.builder().build().isStrictRoutes()).isTrue();
        }

        @Test
        @DisplayName("a builder with strictRoutes(false) and no registry: false")
        void builderOptOut() {
            assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(false).build().isStrictRoutes()).isFalse();
        }

        @Test
        @DisplayName("a builder with strictRoutes(true) and no registry: true")
        void builderExplicitStrict() {
            assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(true).build().isStrictRoutes()).isTrue();
        }

        @Test
        @DisplayName("a registry passed to the builder: true for a strict default, false for a lenient default, false for any other")
        void registryPassedToBuilder() {
            assertThat(ServiceBoundaryCoreModule.builder().registry(new DefaultApiSurfaceRegistry()).build()
                    .isStrictRoutes()).isTrue();
            assertThat(ServiceBoundaryCoreModule.builder().registry(new DefaultApiSurfaceRegistry(false)).build()
                    .isStrictRoutes()).isFalse();
            assertThat(ServiceBoundaryCoreModule.builder().registry(new OtherRegistry()).build()
                    .isStrictRoutes()).isFalse();
        }

        @Test
        @DisplayName("a registry passed to the two-argument constructor: true for a strict default, false for a lenient default, false for any other")
        void registryPassedToConstructor() {
            assertThat(viaConstructor(new DefaultApiSurfaceRegistry()).isStrictRoutes()).isTrue();
            assertThat(viaConstructor(new DefaultApiSurfaceRegistry(false)).isStrictRoutes()).isFalse();
            assertThat(viaConstructor(new OtherRegistry()).isStrictRoutes()).isFalse();
        }

        @Test
        @DisplayName("a builder given a registry ignores strictRoutes(false): the registry decides")
        void builderIgnoresOptOutWhenGivenRegistry() {
            assertThat(ServiceBoundaryCoreModule.builder().registry(new DefaultApiSurfaceRegistry())
                    .strictRoutes(false).build().isStrictRoutes()).isTrue();
            assertThat(ServiceBoundaryCoreModule.builder().strictRoutes(false)
                    .registry(new DefaultApiSurfaceRegistry(false)).build().isStrictRoutes()).isFalse();
            assertThat(ServiceBoundaryCoreModule.builder().registry(new OtherRegistry())
                    .strictRoutes(false).build().isStrictRoutes()).isFalse();
        }

        @Test
        @DisplayName("a builder given a registry refuses strictRoutes(true), whatever the registry is")
        void builderRefusesStrictWithRegistry() {
            for (ApiSurfaceRegistry registry : List.of(new DefaultApiSurfaceRegistry(),
                    new DefaultApiSurfaceRegistry(false), new OtherRegistry())) {
                assertThatThrownBy(() -> ServiceBoundaryCoreModule.builder().registry(registry)
                        .strictRoutes(true).build()).isExactlyInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("the answer follows the registry in use, not the call that built the module")
        void readFromTheRegistry() {
            ServiceBoundaryCoreModule module = ServiceBoundaryCoreModule.builder().strictRoutes(false).build();
            assertThat(module.isStrictRoutes()).isEqualTo(((DefaultApiSurfaceRegistry) module.apiSurfaceRegistry())
                    .isStrictRoutes());
        }
    }

    // ---- permitAll mixed ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("permitAll() mixed with another requirement")
    class PermitAllMixed {

        private final AtomicReference<Probes.Principal> who = new AtomicReference<>();

        private DefaultApiSurfaceRegistry strictRegistry(List<RouteRequirement> requirements) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerFilter(Probes.filter("auth", (ex, m) -> {
                Probes.Principal principal = who.get();
                if (principal != null) {
                    AdmissionContext.current().authenticate(principal);
                }
                return true;
            }));
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/mixed", "T#t", requirements,
                    ex -> ex.respond(HttpStatus.OK)));
            return registry;
        }

        private HttpStatus serve(DefaultApiSurfaceRegistry registry) {
            ScriptedExchange exchange = ScriptedExchange.get("/mixed");
            registry.handle(exchange);
            assertThat(exchange.respondCount()).isEqualTo(1);
            return exchange.statuses().get(0);
        }

        @Test
        @DisplayName("is accepted by a strict registry in either order, and the list is not empty")
        void acceptedInStrictMode() {
            List<RouteRequirement> permitFirst = new ArrayList<>(
                    List.of(RouteRequirement.permitAll(), RouteRequirement.authenticated()));
            List<RouteRequirement> permitLast = new ArrayList<>(
                    List.of(RouteRequirement.authenticated(), RouteRequirement.permitAll()));

            assertThat(strictRegistry(permitFirst).routeCount()).isEqualTo(1);
            assertThat(strictRegistry(permitLast).routeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("with authenticated(): 401 without a principal, served with one, in either order")
        void authenticatedStillEnforced() {
            for (List<RouteRequirement> list : List.of(
                    List.of(RouteRequirement.permitAll(), RouteRequirement.authenticated()),
                    List.of(RouteRequirement.authenticated(), RouteRequirement.permitAll()))) {
                DefaultApiSurfaceRegistry registry = strictRegistry(list);

                who.set(null);
                assertThat(serve(registry)).isEqualTo(HttpStatus.UNAUTHORIZED);
                who.set(new Probes.Principal());
                assertThat(serve(registry)).isEqualTo(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("with a scope requirement: 401 without a principal, 403 without the scope, served with it")
        void scopeStillEnforced() {
            DefaultApiSurfaceRegistry registry = strictRegistry(
                    List.of(RouteRequirement.permitAll(), RouteRequirement.requiringAllScopes(Set.of("w"))));

            who.set(null);
            assertThat(serve(registry)).isEqualTo(HttpStatus.UNAUTHORIZED);
            who.set(new Probes.Principal(Set.of("r")));
            assertThat(serve(registry)).isEqualTo(HttpStatus.FORBIDDEN);
            who.set(new Probes.Principal(Set.of("w")));
            assertThat(serve(registry)).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("alone it declares an anonymous route, served without a principal")
        void aloneIsAnonymous() {
            DefaultApiSurfaceRegistry registry = strictRegistry(List.of(RouteRequirement.permitAll()));

            who.set(null);
            assertThat(serve(registry)).isEqualTo(HttpStatus.OK);
        }
    }
}
