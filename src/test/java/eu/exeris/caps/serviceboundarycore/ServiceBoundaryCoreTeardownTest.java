package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.ServiceLifecycleHooks;
import eu.exeris.caps.serviceboundarycore.internal.CompositeServiceLifecycleHooks;
import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ServiceBoundaryCoreModule teardown and orderly stop")
class ServiceBoundaryCoreTeardownTest {

    // ---- module-level behaviour ----------------------------------------------------------------------------

    private static ApiSurfaceRoute route(String path) {
        return ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern(path).actionName("T#" + path)
                .handler(ex -> ex.respond(HttpStatus.OK)).build();
    }

    private static Exchange get(String path) {
        return new Exchange(HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of()));
    }

    @Test
    @DisplayName("ready twice does not throw")
    void readyTwice() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        module.ready();
        assertThatCode(module::ready).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beginTeardown permits removal and refuses addition on the module's registry")
    void beginTeardownThroughModule() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry registry = module.apiSurfaceRegistry();
        ApiSurfaceRoute r = route("/a");
        Probe f = new Probe("f");
        registry.registerRoute(r);
        registry.registerFilter(f);
        module.ready();

        assertThatThrownBy(() -> registry.unregisterFilter(f)).isInstanceOf(IllegalStateException.class);

        module.beginTeardown();
        module.beginTeardown();

        assertThat(registry.unregisterFilter(f)).isTrue();
        assertThat(registry.unregisterRoute(r)).isTrue();
        assertThatThrownBy(() -> registry.registerFilter(f)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerRoute(r)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("beginTeardown before ready: a module that never became ready cannot become ready afterwards")
    void beginTeardownThenReadyThrows() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        module.apiSurfaceRegistry().registerRoute(route("/a"));
        module.beginTeardown();
        assertThatThrownBy(module::ready).isInstanceOf(IllegalStateException.class);
        Exchange ex = get("/a");
        ((DefaultApiSurfaceRegistry) module.apiSurfaceRegistry()).handle(ex);
        assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("drain enters teardown")
    void drainEntersTeardown() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        Probe f = new Probe("f");
        module.apiSurfaceRegistry().registerFilter(f);
        module.ready();
        module.drain(Duration.ofSeconds(1));
        assertThat(module.apiSurfaceRegistry().unregisterFilter(f)).isTrue();
        Exchange ex = get("/a");
        ((DefaultApiSurfaceRegistry) module.apiSurfaceRegistry()).handle(ex);
        assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("drain enters teardown even when a hook's onDrain throws, and the exception still propagates")
    void drainThrowingHookStillTearsDown() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        Probe f = new Probe("f");
        module.apiSurfaceRegistry().registerFilter(f);
        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override public void onDrain(Duration remaining) throws Exception {
                throw new IllegalStateException("drain failed");
            }
        });
        module.ready();

        assertThatThrownBy(() -> module.drain(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage("drain failed");
        assertThat(module.apiSurfaceRegistry().unregisterFilter(f)).isTrue();
    }

    @Test
    @DisplayName("terminate enters teardown, never throws and still calls every hook in reverse order")
    void terminateEntersTeardown() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        Probe f = new Probe("f");
        module.apiSurfaceRegistry().registerFilter(f);
        List<String> events = new ArrayList<>();
        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override public void onTerminate() { events.add("h1"); }
        });
        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override public void onTerminate() { throw new RuntimeException("boom"); }
        });
        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override public void onTerminate() { events.add("h3"); }
        });
        module.ready();

        assertThatCode(module::terminate).doesNotThrowAnyException();

        assertThat(events).containsExactly("h3", "h1");
        assertThat(module.apiSurfaceRegistry().unregisterFilter(f)).isTrue();
    }

    @Test
    @DisplayName("a custom registry is neither frozen nor torn down by the module")
    void customRegistryUntouched() throws Exception {
        CountingRegistry custom = new CountingRegistry();
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule(custom, new CompositeServiceLifecycleHooks());
        module.ready();
        assertThatCode(module::beginTeardown).doesNotThrowAnyException();
        module.drain(Duration.ofSeconds(1));
        module.terminate();
        assertThat(custom.mutations.get()).isZero();
        // the custom registry keeps full control of its own mutability
        custom.registerFilter(new Probe("x"));
        assertThat(custom.registered.get()).isEqualTo(1);
    }

    // ---- orderly stop --------------------------------------------------------------------------------------

    /** A capability that attaches a filter and a route in initialize and detaches both in terminate. */
    private static final class Consumer implements CapabilityLifecycleHooks {
        final String name;
        final ApiSurfaceRegistry registry;
        final Probe filter;
        final ApiSurfaceRoute route;
        final List<Throwable> detachErrors = new ArrayList<>();

        Consumer(String name, ApiSurfaceRegistry registry) {
            this.name = name;
            this.registry = registry;
            this.filter = new Probe("filter-" + name);
            this.route = route("/" + name);
        }

        @Override public void initialize() {
            registry.registerFilter(filter);
            registry.registerRoute(route);
        }

        @Override public void terminate() {
            try {
                registry.unregisterFilter(filter);
            } catch (Throwable t) {
                detachErrors.add(t);
            }
            try {
                registry.unregisterRoute(route);
            } catch (Throwable t) {
                detachErrors.add(t);
            }
        }
    }

    private static void quietly(List<Throwable> errors, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            errors.add(t);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static void assertNothingAttached(ServiceBoundaryCoreModule owner) {
        assertThat(owner.requestFilterChain().filters()).isEmpty();
        assertThat(owner.apiSurfaceRegistry().routes()).isEmpty();
    }

    @Test
    @DisplayName("owner ready last, drained and terminated first: every consumer detaches without exception")
    void ownerFirstToStop() throws Exception {
        ServiceBoundaryCoreModule owner = new ServiceBoundaryCoreModule();
        Consumer a = new Consumer("a", owner.apiSurfaceRegistry());
        Consumer b = new Consumer("b", owner.apiSurfaceRegistry());
        List<CapabilityLifecycleHooks> startOrder = List.of(a, b, owner);

        for (CapabilityLifecycleHooks m : startOrder) m.initialize();
        for (CapabilityLifecycleHooks m : startOrder) m.ready();

        Exchange serving = get("/a");
        ((DefaultApiSurfaceRegistry) owner.apiSurfaceRegistry()).handle(serving);
        assertThat(serving.status).isEqualTo(HttpStatus.OK);
        assertThat(a.filter.calls.get()).isEqualTo(1);

        List<CapabilityLifecycleHooks> reverse = new ArrayList<>(startOrder);
        java.util.Collections.reverse(reverse);
        List<Throwable> stopErrors = new ArrayList<>();
        for (CapabilityLifecycleHooks m : reverse) quietly(stopErrors, () -> m.drain(Duration.ofSeconds(5)));
        for (CapabilityLifecycleHooks m : reverse) quietly(stopErrors, m::terminate);

        assertThat(stopErrors).isEmpty();
        assertThat(a.detachErrors).isEmpty();
        assertThat(b.detachErrors).isEmpty();
        assertNothingAttached(owner);
    }

    @Test
    @DisplayName("owner first in start order drains and terminates last: every consumer detaches without exception")
    void ownerLastToStop() throws Exception {
        ServiceBoundaryCoreModule owner = new ServiceBoundaryCoreModule();
        Consumer a = new Consumer("a", owner.apiSurfaceRegistry());
        Consumer b = new Consumer("b", owner.apiSurfaceRegistry());
        List<CapabilityLifecycleHooks> startOrder = List.of(owner, a, b);

        for (CapabilityLifecycleHooks m : startOrder) m.initialize();
        for (CapabilityLifecycleHooks m : startOrder) m.ready();

        List<CapabilityLifecycleHooks> reverse = new ArrayList<>(startOrder);
        java.util.Collections.reverse(reverse);
        List<Throwable> stopErrors = new ArrayList<>();
        // all drains before any terminate, as a conductor with enough budget does
        for (CapabilityLifecycleHooks m : reverse) quietly(stopErrors, () -> m.drain(Duration.ofSeconds(5)));
        for (CapabilityLifecycleHooks m : reverse) quietly(stopErrors, m::terminate);

        assertThat(stopErrors).isEmpty();
        assertThat(a.detachErrors).isEmpty();
        assertThat(b.detachErrors).isEmpty();
        assertNothingAttached(owner);
    }

    @Test
    @DisplayName("terminate without drain, owner last: beginTeardown called first gives a clean stop")
    void terminateOnlyAfterBeginTeardown() throws Exception {
        ServiceBoundaryCoreModule owner = new ServiceBoundaryCoreModule();
        Consumer a = new Consumer("a", owner.apiSurfaceRegistry());
        Consumer b = new Consumer("b", owner.apiSurfaceRegistry());
        List<CapabilityLifecycleHooks> startOrder = List.of(owner, a, b);
        for (CapabilityLifecycleHooks m : startOrder) m.initialize();
        for (CapabilityLifecycleHooks m : startOrder) m.ready();

        owner.beginTeardown();
        List<CapabilityLifecycleHooks> reverse = new ArrayList<>(startOrder);
        java.util.Collections.reverse(reverse);
        List<Throwable> stopErrors = new ArrayList<>();
        for (CapabilityLifecycleHooks m : reverse) quietly(stopErrors, m::terminate);

        assertThat(stopErrors).isEmpty();
        assertThat(a.detachErrors).isEmpty();
        assertThat(b.detachErrors).isEmpty();
        assertNothingAttached(owner);
    }

    @Test
    @DisplayName("terminate without drain and without beginTeardown, owner last: the frozen filters stay and keep being enforced, then serving ends with 503")
    void terminateOnlyWithoutBeginTeardownFailsClosed() throws Exception {
        ServiceBoundaryCoreModule owner = new ServiceBoundaryCoreModule();
        Consumer a = new Consumer("a", owner.apiSurfaceRegistry());
        List<CapabilityLifecycleHooks> startOrder = List.of(owner, a);
        for (CapabilityLifecycleHooks m : startOrder) m.initialize();
        for (CapabilityLifecycleHooks m : startOrder) m.ready();

        a.terminate();
        assertThat(a.detachErrors).isNotEmpty().allMatch(IllegalStateException.class::isInstance);
        // still registered, still enforced while serving
        assertThat(owner.requestFilterChain().filters()).containsExactly(a.filter);
        Exchange stillServing = get("/a");
        ((DefaultApiSurfaceRegistry) owner.apiSurfaceRegistry()).handle(stillServing);
        assertThat(stillServing.status).isEqualTo(HttpStatus.OK);
        assertThat(a.filter.calls.get()).isEqualTo(1);

        owner.terminate();
        Exchange ended = get("/a");
        ((DefaultApiSurfaceRegistry) owner.apiSurfaceRegistry()).handle(ended);
        assertThat(ended.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a service that never became ready can be torn down and detached")
    void neverReadyStop() throws Exception {
        ServiceBoundaryCoreModule owner = new ServiceBoundaryCoreModule();
        Consumer a = new Consumer("a", owner.apiSurfaceRegistry());
        a.initialize();
        owner.terminate();
        a.terminate();
        assertThat(a.detachErrors).isEmpty();
        assertNothingAttached(owner);
    }

    // ---- doubles -------------------------------------------------------------------------------------------

    private static class Probe implements AdmissionFilter {
        final String name;
        final AtomicInteger calls = new AtomicInteger();

        Probe(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
        @Override public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            calls.incrementAndGet();
            return true;
        }
    }

    /** A registry the module does not own; counts what is done to it. */
    private static final class CountingRegistry implements ApiSurfaceRegistry, RequestFilterChain {
        final AtomicInteger mutations = new AtomicInteger();
        final AtomicInteger registered = new AtomicInteger();
        private final List<AdmissionFilter> filters = new ArrayList<>();

        @Override public void registerRoute(ApiSurfaceRoute route) { registered.incrementAndGet(); }
        @Override public boolean unregisterRoute(ApiSurfaceRoute route) { mutations.incrementAndGet(); return false; }
        @Override public void registerFilter(AdmissionFilter filter) { filters.add(filter); registered.incrementAndGet(); }
        @Override public boolean unregisterFilter(AdmissionFilter filter) { mutations.incrementAndGet(); return false; }
        @Override public List<AdmissionFilter> filters() { return List.copyOf(filters); }
        @Override public RequestFilterChain filterChain() { return this; }
        @Override public boolean execute(HttpExchange exchange, RouteMatch routeMatch) { return true; }
        @Override public java.util.Optional<RouteMatch> matchRoute(HttpMethod method, String path) { return java.util.Optional.empty(); }
        @Override public List<ApiSurfaceRoute> routes() { return List.of(); }
        @Override public int routeCount() { return 0; }
        @Override public void clear() { mutations.incrementAndGet(); }
        @Override public void handle(HttpExchange exchange) { }
    }

    private static final class Exchange implements HttpExchange {
        private final HttpRequest request;
        HttpStatus status;

        Exchange(HttpRequest request) { this.request = request; }

        @Override public HttpRequest request() { return request; }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public void respond(HttpResponse response) { status = response.status(); }
        @Override public void respond(HttpTypedResponse typedResponse) { status = typedResponse.status(); }
        @Override public void respond(HttpStatus status, Object body) { this.status = status; }
        @Override public void respond(HttpStatus status) { this.status = status; }
    }
}
