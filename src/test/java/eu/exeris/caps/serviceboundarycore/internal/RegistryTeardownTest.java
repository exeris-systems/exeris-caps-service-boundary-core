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
import eu.exeris.kernel.spi.http.RouteRequirement;

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
        return ApiSurfaceRoute.builder().requirements(List.of(RouteRequirement.permitAll()))
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
            reg.registerRoute(ApiSurfaceRoute.builder().requirements(List.of(RouteRequirement.permitAll())).method(HttpMethod.GET).pathPattern("/x")
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
        @DisplayName("a filter removal racing with freeze: removed means gone from the frozen set and from what requests run, refused means present in both (stress)")
        void filterRemovalRacingWithFreezeIsAtomicToRequests() throws Exception {
            raceAgainstFreeze(Race.UNREGISTER_FILTER, 1000);
        }

        @Test
        @DisplayName("a route registration racing with freeze: registered means in the frozen set and served, refused means in neither (stress)")
        void routeRegistrationRacingWithFreezeIsAtomicToRequests() throws Exception {
            raceAgainstFreeze(Race.REGISTER_ROUTE, 1000);
        }

        @Test
        @DisplayName("a route removal racing with freeze: removed means gone from the frozen set and answered 404, refused means present and answered 200 (stress)")
        void routeRemovalRacingWithFreezeIsAtomicToRequests() throws Exception {
            raceAgainstFreeze(Race.UNREGISTER_ROUTE, 1000);
        }

        @Test
        @DisplayName("a registration racing with freeze: accepted means in the frozen set and run by requests, refused means in neither (stress)")
        void registrationRacingWithFreezeIsAtomicToRequests() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                for (int i = 0; i < 1000; i++) {
                    DefaultApiSurfaceRegistry reg = new DefaultApiSurfaceRegistry();
                    reg.registerRoute(route("/things", HttpMethod.GET));
                    List<String> trace = java.util.Collections.synchronizedList(new ArrayList<>());
                    TracingFilter base = new TracingFilter("a-base", trace);
                    TracingFilter racer = new TracingFilter("b-racer", trace);
                    reg.registerFilter(base);
                    CyclicBarrier barrier = new CyclicBarrier(3);
                    AtomicBoolean accepted = new AtomicBoolean();
                    AtomicBoolean refusedAsFrozen = new AtomicBoolean();
                    AtomicInteger sizeAtFreeze = new AtomicInteger(-1);
                    List<List<AdmissionFilter>> reported = java.util.Collections.synchronizedList(new ArrayList<>());
                    List<List<String>> ranDuringRace = java.util.Collections.synchronizedList(new ArrayList<>());
                    Future<?> registering = pool.submit(() -> {
                        await(barrier);
                        try {
                            reg.registerFilter(racer);
                            accepted.set(true);
                        } catch (IllegalStateException expected) {
                            refusedAsFrozen.set(FROZEN_MESSAGE.equals(expected.getMessage()));
                        }
                    });
                    Future<?> freezing = pool.submit(() -> {
                        await(barrier);
                        reg.freeze();
                        sizeAtFreeze.set(reg.filters().size());
                    });
                    Future<?> requesting = pool.submit(() -> {
                        await(barrier);
                        for (int k = 0; k < 3; k++) {
                            List<AdmissionFilter> before = reg.filters();
                            trace.clear();
                            reg.handle(get("/things"));
                            ranDuringRace.add(List.copyOf(trace));
                            reported.add(before);
                        }
                    });
                    registering.get(10, TimeUnit.SECONDS);
                    freezing.get(10, TimeUnit.SECONDS);
                    requesting.get(10, TimeUnit.SECONDS);

                    List<AdmissionFilter> finalSet = reg.filters();
                    trace.clear();
                    reg.handle(get("/things"));
                    List<String> ranAfter = List.copyOf(trace);
                    if (accepted.get()) {
                        assertThat(refusedAsFrozen.get()).isFalse();
                        assertThat(finalSet).as("round %d: accepted but absent from the frozen set", i)
                                .containsExactly(base, racer);
                        assertThat(ranAfter).as("round %d: accepted but not run", i).containsExactly("a-base", "b-racer");
                        assertThat(sizeAtFreeze.get()).isEqualTo(2);
                    } else {
                        assertThat(refusedAsFrozen.get()).as("round %d: refused for another reason", i).isTrue();
                        assertThat(finalSet).as("round %d: refused but present", i).containsExactly(base);
                        assertThat(ranAfter).as("round %d: refused but run", i).containsExactly("a-base");
                        assertThat(sizeAtFreeze.get()).isEqualTo(1);
                    }
                    for (int k = 0; k < ranDuringRace.size(); k++) {
                        List<String> expected = reported.get(k).stream().map(f -> f.name()).toList();
                        List<String> ran = ranDuringRace.get(k);
                        // a request reads the set once; filters() may have grown by one registration in between
                        assertThat(ran).as("round %d request %d", i, k)
                                .isIn(List.of("a-base"), List.of("a-base", "b-racer"));
                        assertThat(expected.size()).isLessThanOrEqualTo(ran.size());
                    }
                }
            } finally {
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

    private enum Race { UNREGISTER_FILTER, REGISTER_ROUTE, UNREGISTER_ROUTE }

    /**
     * Runs a mutation, a freeze and a stream of requests together. Whatever the interleaving, once the mutation
     * and the freeze have returned, the mutation is either reflected in the frozen set and in what requests see,
     * or it was refused with the frozen exception and is reflected in neither.
     */
    private void raceAgainstFreeze(Race race, int rounds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < rounds; i++) {
                DefaultApiSurfaceRegistry reg = new DefaultApiSurfaceRegistry();
                List<String> trace = java.util.Collections.synchronizedList(new ArrayList<>());
                TracingFilter base = new TracingFilter("a-base", trace);
                TracingFilter victim = new TracingFilter("b-victim", trace);
                ApiSurfaceRoute things = route("/things", HttpMethod.GET);
                ApiSurfaceRoute other = route("/other", HttpMethod.GET);
                reg.registerRoute(things);
                reg.registerFilter(base);
                if (race == Race.UNREGISTER_FILTER) {
                    reg.registerFilter(victim);
                }
                if (race == Race.UNREGISTER_ROUTE) {
                    reg.registerRoute(other);
                }
                String requestPath = race == Race.UNREGISTER_FILTER ? "/things" : "/other";
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicBoolean reportedSuccess = new AtomicBoolean();
                java.util.concurrent.atomic.AtomicReference<String> refusal = new java.util.concurrent.atomic.AtomicReference<>();
                AtomicInteger sizeAtFreeze = new AtomicInteger(-1);
                Future<?> mutating = pool.submit(() -> {
                    await(barrier);
                    try {
                        switch (race) {
                            case UNREGISTER_FILTER -> reportedSuccess.set(reg.unregisterFilter(victim));
                            case REGISTER_ROUTE -> {
                                reg.registerRoute(other);
                                reportedSuccess.set(true);
                            }
                            case UNREGISTER_ROUTE -> reportedSuccess.set(reg.unregisterRoute(other));
                        }
                    } catch (IllegalStateException refused) {
                        refusal.set(refused.getMessage());
                    }
                });
                Future<?> freezing = pool.submit(() -> {
                    await(barrier);
                    reg.freeze();
                    sizeAtFreeze.set(race == Race.UNREGISTER_FILTER ? reg.filters().size() : reg.routeCount());
                });
                Future<?> requesting = pool.submit(() -> {
                    await(barrier);
                    for (int k = 0; k < 3; k++) {
                        Exchange ex = get(requestPath);
                        reg.handle(ex);
                        assertThat(ex.responses).as("answered exactly once").hasSize(1);
                    }
                });
                mutating.get(10, TimeUnit.SECONDS);
                freezing.get(10, TimeUnit.SECONDS);
                requesting.get(10, TimeUnit.SECONDS);

                boolean present = switch (race) {
                    case UNREGISTER_FILTER -> reg.filters().contains(victim);
                    case REGISTER_ROUTE, UNREGISTER_ROUTE -> reg.routes().contains(other);
                };
                trace.clear();
                Exchange served = get(requestPath);
                reg.handle(served);
                boolean requestSeesIt = switch (race) {
                    case UNREGISTER_FILTER -> trace.contains("b-victim");
                    case REGISTER_ROUTE, UNREGISTER_ROUTE -> served.status == HttpStatus.OK;
                };
                boolean mutationRemoves = race != Race.REGISTER_ROUTE;
                if (reportedSuccess.get()) {
                    assertThat(refusal.get()).as("round %d: reported success and refused", i).isNull();
                    assertThat(present).as("round %d: %s reported success but the frozen set disagrees", i, race)
                            .isEqualTo(!mutationRemoves);
                    assertThat(requestSeesIt).as("round %d: %s reported success but requests disagree", i, race)
                            .isEqualTo(!mutationRemoves);
                } else {
                    assertThat(refusal.get()).as("round %d: %s neither succeeded nor was refused as frozen", i, race)
                            .isEqualTo(FROZEN_MESSAGE);
                    assertThat(present).as("round %d: %s refused but the frozen set changed", i, race)
                            .isEqualTo(mutationRemoves);
                    assertThat(requestSeesIt).as("round %d: %s refused but requests see a change", i, race)
                            .isEqualTo(mutationRemoves);
                }
                int finalSize = race == Race.UNREGISTER_FILTER ? reg.filters().size() : reg.routeCount();
                assertThat(finalSize).as("round %d: the set changed after freeze returned", i)
                        .isEqualTo(sizeAtFreeze.get());
            }
        } finally {
            pool.shutdownNow();
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

    private static final class TracingFilter implements AdmissionFilter {
        private final String name;
        private final List<String> trace;

        TracingFilter(String name, List<String> trace) {
            this.name = name;
            this.trace = trace;
        }

        @Override public String name() { return name; }
        @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
        @Override public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            trace.add(name);
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
