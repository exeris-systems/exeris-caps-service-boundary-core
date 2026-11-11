package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultApiSurfaceRegistry phases: open, frozen, teardown")
class RegistryTeardownTest {

    private static final String FROZEN_MESSAGE = "The registry is frozen and can no longer be modified";

    private DefaultApiSurfaceRegistry registry;
    private AtomicInteger handlerCalls;
    private ApiSurfaceRoute route;
    private CountingFilter first;
    private CountingFilter second;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        handlerCalls = new AtomicInteger();
        route = route("/things", HttpMethod.GET);
        first = new CountingFilter("a-first");
        second = new CountingFilter("b-second");
        registry.registerRoute(route);
        registry.registerFilter(first);
        registry.registerFilter(second);
    }

    private ApiSurfaceRoute route(String path, HttpMethod method) {
        return ApiSurfaceRoute.builder()
                .method(method)
                .pathPattern(path)
                .actionName("Test#" + path)
                .handler(ex -> {
                    handlerCalls.incrementAndGet();
                    ex.respond(HttpStatus.OK);
                })
                .build();
    }

    private static Exchange get(String path) {
        return new Exchange(HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of()));
    }

    // ---- before ready --------------------------------------------------------------------------------------

    @Nested
    @DisplayName("open phase")
    class Open {

        @Test
        @DisplayName("registration and removal behave as before: unregister then re-register is allowed")
        void openMutationsWork() {
            assertThat(registry.isFrozen()).isFalse();
            assertThat(registry.isTearingDown()).isFalse();
            assertThat(registry.unregisterFilter(first)).isTrue();
            registry.registerFilter(first);
            assertThat(registry.unregisterRoute(route)).isTrue();
            registry.registerRoute(route);
            assertThat(registry.filters()).containsExactly(first, second);
            assertThat(registry.routes()).containsExactly(route);
        }

        @Test
        @DisplayName("requests are dispatched through filters and handler")
        void openDispatches() {
            Exchange ex = get("/things");
            registry.handle(ex);
            assertThat(ex.status).isEqualTo(HttpStatus.OK);
            assertThat(first.calls.get()).isEqualTo(1);
            assertThat(second.calls.get()).isEqualTo(1);
            assertThat(handlerCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("open may go straight to teardown")
        void openToTeardown() {
            registry.beginTeardown();
            assertThat(registry.isTearingDown()).isTrue();
            assertThat(registry.unregisterFilter(first)).isTrue();
        }
    }

    // ---- frozen --------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("frozen phase")
    class Frozen {

        @BeforeEach
        void freeze() {
            registry.freeze();
        }

        @Test
        @DisplayName("every mutator throws the frozen message and leaves routes and filters unchanged")
        void everyMutatorRefused() {
            ApiSurfaceRoute other = route("/other", HttpMethod.GET);
            CountingFilter extra = new CountingFilter("c-extra");

            assertThatThrownBy(() -> registry.registerRoute(other))
                    .isInstanceOf(IllegalStateException.class).hasMessage(FROZEN_MESSAGE);
            assertThatThrownBy(() -> registry.registerFilter(extra))
                    .isInstanceOf(IllegalStateException.class).hasMessage(FROZEN_MESSAGE);
            assertThatThrownBy(() -> registry.unregisterRoute(route))
                    .isInstanceOf(IllegalStateException.class).hasMessage(FROZEN_MESSAGE);
            assertThatThrownBy(() -> registry.unregisterFilter(first))
                    .isInstanceOf(IllegalStateException.class).hasMessage(FROZEN_MESSAGE);
            assertThatThrownBy(registry::clear)
                    .isInstanceOf(IllegalStateException.class).hasMessage(FROZEN_MESSAGE);

            assertThat(registry.routes()).containsExactly(route);
            assertThat(registry.filters()).containsExactly(first, second);
            assertThat(registry.isFrozen()).isTrue();
            assertThat(registry.isTearingDown()).isFalse();
        }

        @Test
        @DisplayName("requests are dispatched exactly as before: 200, 404, 405")
        void frozenDispatches() {
            Exchange ok = get("/things");
            registry.handle(ok);
            assertThat(ok.status).isEqualTo(HttpStatus.OK);
            assertThat(first.calls.get()).isEqualTo(1);
            assertThat(second.calls.get()).isEqualTo(1);
            assertThat(handlerCalls.get()).isEqualTo(1);

            Exchange missing = get("/nothing");
            registry.handle(missing);
            assertThat(missing.status).isEqualTo(HttpStatus.NOT_FOUND);

            Exchange wrongMethod = new Exchange(
                    HttpRequest.noBody(HttpMethod.POST, "/things", HttpVersion.HTTP_1_1, List.of()));
            registry.handle(wrongMethod);
            assertThat(wrongMethod.status).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("freeze is idempotent")
        void freezeIdempotent() {
            assertThatCode(registry::freeze).doesNotThrowAnyException();
            assertThat(registry.isFrozen()).isTrue();
            assertThat(registry.isTearingDown()).isFalse();
        }
    }

    // ---- teardown ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("teardown phase")
    class Teardown {

        @BeforeEach
        void ready() {
            registry.freeze();
            registry.beginTeardown();
        }

        @Test
        @DisplayName("removing a filter returns true, does not throw and the filter is gone")
        void unregisterFilterWorks() {
            assertThat(registry.isTearingDown()).isTrue();
            assertThat(registry.unregisterFilter(first)).isTrue();
            assertThat(registry.filters()).containsExactly(second);
        }

        @Test
        @DisplayName("removing a route returns true and the route is gone")
        void unregisterRouteWorks() {
            assertThat(registry.unregisterRoute(route)).isTrue();
            assertThat(registry.routes()).isEmpty();
            assertThat(registry.routeCount()).isZero();
        }

        @Test
        @DisplayName("clear empties routes and filters without throwing")
        void clearWorks() {
            assertThatCode(registry::clear).doesNotThrowAnyException();
            assertThat(registry.routes()).isEmpty();
            assertThat(registry.filters()).isEmpty();
        }

        @Test
        @DisplayName("a second removal of the same filter or route returns false and does not throw")
        void secondRemovalFalse() {
            assertThat(registry.unregisterFilter(first)).isTrue();
            assertThat(registry.unregisterFilter(first)).isFalse();
            assertThat(registry.unregisterRoute(route)).isTrue();
            assertThat(registry.unregisterRoute(route)).isFalse();
        }

        @Test
        @DisplayName("additions stay refused: new, re-added and replacing registrations throw")
        void additionsRefused() {
            assertThatThrownBy(() -> registry.registerRoute(route("/new", HttpMethod.GET)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> registry.registerFilter(new CountingFilter("c-new")))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(registry.unregisterFilter(first)).isTrue();
            assertThatThrownBy(() -> registry.registerFilter(first))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(registry.unregisterRoute(route)).isTrue();
            assertThatThrownBy(() -> registry.registerRoute(route))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(registry.filters()).containsExactly(second);
            assertThat(registry.routes()).isEmpty();
        }

        @Test
        @DisplayName("a request for a matching route answers 503 and runs no filter and no handler")
        void matchingRouteAnswers503() {
            Exchange ex = get("/things");
            registry.handle(ex);
            assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(first.calls.get()).isZero();
            assertThat(second.calls.get()).isZero();
            assertThat(handlerCalls.get()).isZero();
        }

        @Test
        @DisplayName("an unmatched path and a wrong method also answer 503, not 404 or 405")
        void unmatchedAnswers503() {
            Exchange missing = get("/nothing");
            registry.handle(missing);
            assertThat(missing.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            Exchange wrongMethod = new Exchange(
                    HttpRequest.noBody(HttpMethod.POST, "/things", HttpVersion.HTTP_1_1, List.of()));
            registry.handle(wrongMethod);
            assertThat(wrongMethod.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("503 is answered once after the route table has been emptied too")
        void answers503AfterRouteRemoval() {
            registry.unregisterRoute(route);
            Exchange ex = get("/things");
            registry.handle(ex);
            assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.responses).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("filterChain().execute returns false, answers 503 and invokes no filter")
        void executeRefuses() {
            Exchange ex = get("/things");
            boolean admitted = registry.filterChain().execute(ex, new RouteMatch(route, Map.of()));
            assertThat(admitted).isFalse();
            assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(first.calls.get()).isZero();
            assertThat(second.calls.get()).isZero();
        }

        @Test
        @DisplayName("beginTeardown is idempotent")
        void beginTeardownIdempotent() {
            assertThatCode(registry::beginTeardown).doesNotThrowAnyException();
            assertThatCode(registry::beginTeardown).doesNotThrowAnyException();
            assertThat(registry.isTearingDown()).isTrue();
        }

        @Test
        @DisplayName("freeze after teardown throws and the registry stays in teardown")
        void freezeAfterTeardownThrows() {
            assertThatThrownBy(registry::freeze).isInstanceOf(IllegalStateException.class);
            assertThat(registry.isTearingDown()).isTrue();
            Exchange ex = get("/things");
            registry.handle(ex);
            assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("beginTeardown never throws in any phase")
    void beginTeardownNeverThrows() {
        assertThatCode(registry::beginTeardown).doesNotThrowAnyException();
        DefaultApiSurfaceRegistry other = new DefaultApiSurfaceRegistry();
        other.freeze();
        assertThatCode(other::beginTeardown).doesNotThrowAnyException();
        assertThatCode(other::beginTeardown).doesNotThrowAnyException();
    }

    // ---- scripted interleavings ----------------------------------------------------------------------------

    @Nested
    @DisplayName("scripted interleavings")
    class Interleavings {

        @Test
        @DisplayName("a request inside admission when teardown begins and a filter is removed still runs its whole snapshot")
        void inFlightRequestKeepsSnapshot() throws Exception {
            DefaultApiSurfaceRegistry reg = new DefaultApiSurfaceRegistry();
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GateFilter gate = new GateFilter("a-gate", inside, release);
            CountingFilter guard = new CountingFilter("b-guard");
            AtomicInteger handled = new AtomicInteger();
            reg.registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.GET).pathPattern("/x")
                    .actionName("X#get").handler(ex -> {
                        handled.incrementAndGet();
                        ex.respond(HttpStatus.OK);
                    }).build());
            reg.registerFilter(gate);
            reg.registerFilter(guard);
            reg.freeze();

            Exchange ex = get("/x");
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> request = pool.submit(() -> reg.handle(ex));
                assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

                reg.beginTeardown();
                assertThat(reg.unregisterFilter(guard)).isTrue();
                release.countDown();

                request.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }

            assertThat(guard.calls.get()).as("the filter removed during admission still ran").isEqualTo(1);
            assertThat(handled.get()).isEqualTo(1);
            assertThat(ex.status).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a request that starts after teardown began runs no filter and answers 503")
        void requestAfterTeardownRefused() {
            registry.freeze();
            registry.beginTeardown();
            registry.unregisterFilter(second);
            Exchange ex = get("/things");
            registry.handle(ex);
            assertThat(ex.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(first.calls.get()).isZero();
            assertThat(second.calls.get()).isZero();
            assertThat(handlerCalls.get()).isZero();
        }

        @Test
        @DisplayName("a registration holding the mutation lock completes before a concurrent freeze returns")
        void registrationCompletesBeforeFreezeReturns() throws Exception {
            DefaultApiSurfaceRegistry reg = new DefaultApiSurfaceRegistry();
            CountDownLatch insideCheck = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // registerFilter compares the new name with each existing name while the registration is in
            // progress; blocking there holds the registration open at a call the class already makes.
            AtomicBoolean blockNext = new AtomicBoolean();
            AdmissionFilter existing = new AdmissionFilter() {
                @Override public String name() {
                    if (blockNext.get()) {
                        insideCheck.countDown();
                        await(release);
                    }
                    return "existing";
                }
                @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
                @Override public boolean filter(HttpExchange e, RouteMatch m) { return true; }
            };
            reg.registerFilter(existing);
            blockNext.set(true);
            CountingFilter late = new CountingFilter("late");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> registering = pool.submit(() -> reg.registerFilter(late));
                assertThat(insideCheck.await(10, TimeUnit.SECONDS)).isTrue();

                AtomicInteger sizeWhenFrozen = new AtomicInteger(-1);
                Thread freezer = new Thread(() -> {
                    reg.freeze();
                    sizeWhenFrozen.set(reg.filters().size());
                });
                freezer.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (freezer.getState() == Thread.State.RUNNABLE || freezer.getState() == Thread.State.NEW) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.onSpinWait();
                }
                assertThat(sizeWhenFrozen.get()).as("freeze must wait for the registration in progress").isEqualTo(-1);

                release.countDown();
                registering.get(10, TimeUnit.SECONDS);
                freezer.join(10_000);
                assertThat(freezer.isAlive()).isFalse();

                assertThat(sizeWhenFrozen.get()).isEqualTo(2);
                assertThat(reg.filters()).contains(late).hasSize(2);
                assertThatThrownBy(() -> reg.registerFilter(new CountingFilter("after")))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a registration racing with freeze either lands before ready or throws, never after (stress)")
        void registrationRacingWithFreeze() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 300; i++) {
                    DefaultApiSurfaceRegistry reg = new DefaultApiSurfaceRegistry();
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    CountingFilter f = new CountingFilter("racer");
                    AtomicBoolean registered = new AtomicBoolean();
                    AtomicInteger sizeAtReady = new AtomicInteger(-1);
                    Future<?> reg1 = pool.submit(() -> {
                        await(barrier);
                        try {
                            reg.registerFilter(f);
                            registered.set(true);
                        } catch (IllegalStateException expected) {
                            // refused because ready was first
                        }
                    });
                    Future<?> reg2 = pool.submit(() -> {
                        await(barrier);
                        reg.freeze();
                        sizeAtReady.set(reg.filters().size());
                    });
                    reg1.get(10, TimeUnit.SECONDS);
                    reg2.get(10, TimeUnit.SECONDS);
                    int finalSize = reg.filters().size();
                    assertThat(finalSize).as("iteration %d: filters changed after ready returned", i)
                            .isEqualTo(sizeAtReady.get());
                    assertThat(finalSize == 1).isEqualTo(registered.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- doubles -------------------------------------------------------------------------------------------

    private static class CountingFilter implements AdmissionFilter {
        final String name;
        final AtomicInteger calls = new AtomicInteger();

        CountingFilter(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
        @Override public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            calls.incrementAndGet();
            return true;
        }
    }

    private static final class GateFilter extends CountingFilter {
        private final CountDownLatch inside;
        private final CountDownLatch release;

        GateFilter(String name, CountDownLatch inside, CountDownLatch release) {
            super(name);
            this.inside = inside;
            this.release = release;
        }

        @Override public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            inside.countDown();
            await(release);
            return super.filter(exchange, routeMatch);
        }
    }

    private static final class Exchange implements HttpExchange {
        private final HttpRequest request;
        HttpStatus status;
        final List<HttpStatus> responses = new ArrayList<>();

        Exchange(HttpRequest request) { this.request = request; }

        @Override public HttpRequest request() { return request; }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public void respond(HttpResponse response) { record(response.status()); }
        @Override public void respond(HttpTypedResponse typedResponse) { record(typedResponse.status()); }
        @Override public void respond(HttpStatus status, Object body) { record(status); }
        @Override public void respond(HttpStatus status) { record(status); }

        private void record(HttpStatus s) {
            status = s;
            responses.add(s);
        }
    }
}
