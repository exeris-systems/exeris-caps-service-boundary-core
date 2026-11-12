package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Statements the README and the roadmap make about behaviour, each decided by a test. */
@DisplayName("Documented behaviour: README and roadmap statements")
class DocumentedContractTest {

    private static ApiSurfaceRoute route(HttpMethod method, String pattern) {
        return new ApiSurfaceRoute(method, pattern, "T#t", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.OK));
    }

    @Nested
    @DisplayName("README: no matched exchange is left unanswered")
    class Answers {

        @Test
        @DisplayName("the 500 answers are a bare status, as the 404 and 503 answers")
        void bareStatusAnswers() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/silent", "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> { }));

            ScriptedExchange silent = ScriptedExchange.get("/silent");
            registry.handle(silent);
            ScriptedExchange missing = ScriptedExchange.get("/missing");
            registry.handle(missing);
            registry.beginTeardown();
            ScriptedExchange torn = ScriptedExchange.get("/silent");
            registry.handle(torn);

            assertThat(silent.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(missing.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.NOT_FOUND));
            assertThat(torn.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("the 405 answer is not a bare status: it is a full response carrying an Allow header")
        void methodNotAllowedCarriesAllow() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/r"));
            ScriptedExchange exchange = ScriptedExchange.of(HttpMethod.POST, "/r", List.of());

            registry.handle(exchange);

            assertThat(exchange.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.RESPONSE, HttpStatus.METHOD_NOT_ALLOWED));
        }

        @Test
        @DisplayName("a host that drives execute itself gets true for a passing chain and answers the exchange itself")
        void hostDrivenExecute() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/r"));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/r").orElseThrow();
            ScriptedExchange exchange = ScriptedExchange.get("/r");

            assertThat(registry.execute(exchange, match)).isTrue();
            assertThat(exchange.respondCount()).isZero();
        }
    }

    @Nested
    @DisplayName("README and roadmap: unmatched requests skip admission")
    class UnmatchedSkipAdmission {

        @Test
        @DisplayName("a filter is not called for a 404 or a 405")
        void filterNotCalledForUnmatched() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            CountingFilter filter = Probes.filter("counter", (ex, m) -> true);
            registry.registerFilter(filter);
            registry.registerRoute(route(HttpMethod.GET, "/r"));

            ScriptedExchange notFound = ScriptedExchange.get("/nothing");
            registry.handle(notFound);
            ScriptedExchange wrongMethod = ScriptedExchange.of(HttpMethod.POST, "/r", List.of());
            registry.handle(wrongMethod);

            assertThat(notFound.statuses()).containsExactly(HttpStatus.NOT_FOUND);
            assertThat(wrongMethod.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(filter.calls).hasValue(0);
        }
    }

    @Nested
    @DisplayName("roadmap: admission outside handle() enforces no route requirements")
    class ExecuteEnforcesNoRequirements {

        @Test
        @DisplayName("execute returns true for a route that requires authentication when no principal was recorded")
        void executeSkipsRequirements() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/r", "T#t",
                    List.of(RouteRequirement.authenticated()), ex -> ex.respond(HttpStatus.OK)));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/r").orElseThrow();
            ScriptedExchange exchange = ScriptedExchange.get("/r");

            assertThat(registry.execute(exchange, match)).isTrue();
            assertThat(exchange.respondCount()).isZero();
        }

        @Test
        @DisplayName("execute leaves a bound admission context open: it can still authenticate afterwards")
        void executeDoesNotSeal() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/r"));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/r").orElseThrow();
            DefaultAdmissionContext context = DefaultAdmissionContext.open();

            DefaultAdmissionContext.callWith(context, () -> {
                registry.execute(ScriptedExchange.get("/r"), match);
                context.authenticate(new Probes.Principal());
                return null;
            });

            assertThat(context.requestContext().principal()).isPresent();
        }
    }

    @Nested
    @DisplayName("roadmap: path matching works on the raw path string")
    class RawPathMatching {

        @Test
        @DisplayName("percent-encoding is not decoded, dot segments are not resolved, repeated slashes are not collapsed")
        void rawMatching() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/abc"));

            assertThat(registry.matchRoute(HttpMethod.GET, "/abc")).isPresent();
            assertThat(registry.matchRoute(HttpMethod.GET, "/%61bc")).isEmpty();
            assertThat(registry.matchRoute(HttpMethod.GET, "/x/../abc")).isEmpty();
            assertThat(registry.matchRoute(HttpMethod.GET, "//abc")).isEmpty();
        }

        @Test
        @DisplayName("the query string and one trailing slash are stripped, and nothing else")
        void queryAndTrailingSlash() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/abc"));

            assertThat(registry.matchRoute(HttpMethod.GET, "/abc?x=1")).isPresent();
            assertThat(registry.matchRoute(HttpMethod.GET, "/abc/")).isPresent();
            assertThat(registry.matchRoute(HttpMethod.GET, "/abc//")).isEmpty();
        }

        @Test
        @DisplayName("a {name} segment accepts any run of characters except the slash, and not an empty one")
        void parameterSegmentAcceptsAnythingButSlash() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/a/{id}"));

            assertThat(registry.matchRoute(HttpMethod.GET, "/a/%2F..%00 x;y")).isPresent();
            assertThat(registry.matchRoute(HttpMethod.GET, "/a/b/c")).isEmpty();
            assertThat(registry.matchRoute(HttpMethod.GET, "/a/")).isEmpty();
        }
    }

    @Nested
    @DisplayName("roadmap: methods are matched exactly")
    class ExactMethods {

        @Test
        @DisplayName("HEAD does not fall back to GET and OPTIONS is answered 405")
        void headAndOptions() {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route(HttpMethod.GET, "/r"));

            ScriptedExchange head = ScriptedExchange.of(HttpMethod.HEAD, "/r", List.of());
            registry.handle(head);
            ScriptedExchange options = ScriptedExchange.of(HttpMethod.OPTIONS, "/r", List.of());
            registry.handle(options);

            assertThat(head.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(options.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("roadmap: parameterised routes match in registration order")
    class RegistrationOrder {

        @Test
        @DisplayName("the first registered of two differently shaped matching routes wins, whichever is more specific")
        void firstRegisteredWins() {
            ApiSurfaceRoute specific = route(HttpMethod.GET, "/a/{id}/x");
            ApiSurfaceRoute general = route(HttpMethod.GET, "/a/{name}/{rest}");

            DefaultApiSurfaceRegistry specificFirst = new DefaultApiSurfaceRegistry();
            specificFirst.registerRoute(specific);
            specificFirst.registerRoute(general);
            DefaultApiSurfaceRegistry generalFirst = new DefaultApiSurfaceRegistry();
            generalFirst.registerRoute(general);
            generalFirst.registerRoute(specific);

            assertThat(specificFirst.matchRoute(HttpMethod.GET, "/a/1/x").orElseThrow().route()).isSameAs(specific);
            assertThat(generalFirst.matchRoute(HttpMethod.GET, "/a/1/x").orElseThrow().route()).isSameAs(general);
        }
    }

    @Nested
    @DisplayName("roadmap: lifecycle failures are not unwound by the hooks")
    class LifecycleNotUnwound {

        @Test
        @DisplayName("a failing initialize stops the later hooks and terminates none of the earlier ones")
        void initializeFailureIsNotUnwound() {
            List<String> events = new ArrayList<>();
            CompositeServiceLifecycleHooks hooks = new CompositeServiceLifecycleHooks();
            hooks.registerHook(hook("h1", events, false));
            hooks.registerHook(hook("h2", events, true));
            hooks.registerHook(hook("h3", events, false));

            assertThatThrownBy(hooks::onServiceInitialize).isInstanceOf(IllegalStateException.class);

            assertThat(events).containsExactly("h1:init", "h2:init");
        }

        @Test
        @DisplayName("a failing ready stops the later hooks and terminates none of the earlier ones")
        void readyFailureIsNotUnwound() {
            List<String> events = new ArrayList<>();
            CompositeServiceLifecycleHooks hooks = new CompositeServiceLifecycleHooks();
            hooks.registerHook(new BoundaryServiceHook() {
                @Override public void onReady() { events.add("h1:ready"); }
                @Override public void onTerminate() { events.add("h1:term"); }
            });
            hooks.registerHook(new BoundaryServiceHook() {
                @Override public void onReady() { throw new IllegalStateException("no"); }
            });

            assertThatThrownBy(hooks::onServiceReady).isInstanceOf(IllegalStateException.class);

            assertThat(events).containsExactly("h1:ready");
        }

        private BoundaryServiceHook hook(String name, List<String> events, boolean failing) {
            return new BoundaryServiceHook() {
                @Override public void onInitialize() {
                    events.add(name + ":init");
                    if (failing) {
                        throw new IllegalStateException(name);
                    }
                }

                @Override public void onTerminate() { events.add(name + ":term"); }
            };
        }
    }

    @Nested
    @DisplayName("roadmap: a composition that skips the boundary's drain leaves attached filters in place")
    class SkippedDrain {

        @Test
        @DisplayName("while frozen, unregisterFilter throws and the filter stays enforced; terminate then ends serving with 503")
        void filterStaysUntilTeardown() throws Exception {
            ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
            CountingFilter filter = new CountingFilter("attached", AdmissionPhase.AUTHENTICATION, (ex, m) -> true);
            module.apiSurfaceRegistry().registerFilter(filter);
            module.apiSurfaceRegistry().registerRoute(route(HttpMethod.GET, "/r"));
            module.ready();

            assertThatThrownBy(() -> module.apiSurfaceRegistry().unregisterFilter(filter))
                    .isInstanceOf(IllegalStateException.class);
            ScriptedExchange served = ScriptedExchange.get("/r");
            module.apiSurfaceRegistry().handle(served);
            assertThat(filter.calls).hasValue(1);
            assertThat(served.statuses()).containsExactly(HttpStatus.OK);

            module.terminate();
            ScriptedExchange refused = ScriptedExchange.get("/r");
            module.apiSurfaceRegistry().handle(refused);
            assertThat(refused.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(module.apiSurfaceRegistry().unregisterFilter(filter)).isTrue();
        }
    }
}
