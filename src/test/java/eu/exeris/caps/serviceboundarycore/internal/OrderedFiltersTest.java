package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures.Shifty;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The ordered admission filters")
class OrderedFiltersTest {

    private static final ThreadLocal<List<String>> RAN = ThreadLocal.withInitial(ArrayList::new);

    private static Shifty filter(String name, AdmissionPhase phase) {
        return new Shifty(name, phase, n -> RAN.get().add(n));
    }

    /** Runs one request on this thread and returns the status and the filters that ran, in order. */
    private static Outcome serve(DefaultApiSurfaceRegistry registry) {
        RAN.get().clear();
        ScriptedExchange exchange = ScriptedExchange.get("/p");
        registry.handle(exchange);
        List<HttpStatus> statuses = exchange.statuses();
        return new Outcome(statuses.isEmpty() ? null : statuses.get(statuses.size() - 1), List.copyOf(RAN.get()),
                statuses.size());
    }

    private record Outcome(HttpStatus status, List<String> ran, int answers) { }

    private static final Comparator<Shifty> MODEL_ORDER = (a, b) -> {
        int byPhase = Integer.compare(a.phase.ordinal(), b.phase.ordinal());
        return byPhase != 0 ? byPhase : a.name.compareTo(b.name);
    };

    private static void ensureRoute(DefaultApiSurfaceRegistry registry) {
        if (registry.routeCount() == 0) {
            registry.registerRoute(Fixtures.open("/p"));
        }
    }

    @Test
    @DisplayName("after every register, unregister and clear of a seeded sequence the run order and filters() equal a sorted model")
    void runOrderEqualsIndependentModel() {
        String[] names = {"a", "b", "B", "a-1", "a_1", "Z", "m", "0", "~", "aa", "ab", "é"};
        for (int seed = 0; seed < 300; seed++) {
            Random random = new Random(seed);
            List<Shifty> pool = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                AdmissionPhase phase = AdmissionPhase.values()[random.nextInt(AdmissionPhase.values().length)];
                pool.add(filter(names[i], phase));
            }
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            List<Shifty> model = new ArrayList<>();
            for (int step = 0; step < 40; step++) {
                int op = random.nextInt(10);
                Shifty subject = pool.get(random.nextInt(pool.size()));
                if (op < 5) {
                    if (model.contains(subject)) {
                        assertThatThrownBy(() -> registry.registerFilter(subject))
                                .isInstanceOf(IllegalStateException.class);
                    } else {
                        registry.registerFilter(subject);
                        model.add(subject);
                    }
                } else if (op < 9) {
                    assertThat(registry.unregisterFilter(subject)).isEqualTo(model.remove(subject));
                } else {
                    registry.clear();
                    model.clear();
                }
                List<Shifty> expected = new ArrayList<>(model);
                expected.sort(MODEL_ORDER);
                assertThat(registry.filters()).as("seed %d step %d", seed, step).containsExactlyElementsOf(expected);
                ensureRoute(registry);
                Outcome outcome = serve(registry);
                assertThat(outcome.status()).isEqualTo(HttpStatus.OK);
                assertThat(outcome.ran()).as("seed %d step %d", seed, step)
                        .containsExactlyElementsOf(expected.stream().map(f -> f.name).toList());
            }
        }
    }

    @Test
    @DisplayName("filters() is an unmodifiable snapshot, independent of later registrations, in run order")
    void snapshotIsUnmodifiableAndIndependent() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty late = filter("a-late", AdmissionPhase.PRE_ADMISSION);
        registry.registerFilter(filter("z", AdmissionPhase.POST_ADMISSION));
        registry.registerFilter(filter("b", AdmissionPhase.AUTHENTICATION));
        List<AdmissionFilter> snapshot = registry.filters();
        assertThatThrownBy(() -> snapshot.add(late)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.set(0, late)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.sort(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Collections.reverse(snapshot)).isInstanceOf(UnsupportedOperationException.class);

        registry.registerFilter(late);
        assertThat(snapshot).extracting(AdmissionFilter::name).containsExactly("b", "z");
        assertThat(registry.filters()).extracting(AdmissionFilter::name).containsExactly("a-late", "b", "z");
        assertThat(registry.filters()).isNotSameAs(registry.filters());

        registry.registerRoute(Fixtures.open("/p"));
        assertThat(serve(registry).ran()).containsExactly("a-late", "b", "z");
        registry.clear();
        assertThat(snapshot).hasSize(2);
        assertThat(registry.filters()).isEmpty();
    }

    @Test
    @DisplayName("removal and clear are refused while FROZEN and leave the filters in place; absent filters answer false while OPEN")
    void removalRefusedWhileFrozen() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty one = filter("one", AdmissionPhase.AUTHENTICATION);
        Shifty absent = filter("absent", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(one);
        assertThat(registry.unregisterFilter(absent)).isFalse();
        assertThat(registry.filters()).containsExactly(one);
        registry.registerRoute(Fixtures.open("/p"));
        registry.freeze();
        assertThatThrownBy(() -> registry.unregisterFilter(one)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.unregisterFilter(absent)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::clear).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerFilter(absent)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.filters()).containsExactly(one);
        assertThat(serve(registry).ran()).containsExactly("one");
    }

    @Test
    @DisplayName("after beginTeardown removed filters do not run and requests get 503 without running a filter")
    void teardownAnswers503WithoutFilters() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<Shifty> all = List.of(filter("a", AdmissionPhase.AUTHENTICATION), filter("b", AdmissionPhase.AUTHORIZATION),
                filter("c", AdmissionPhase.POST_ADMISSION));
        all.forEach(registry::registerFilter);
        registry.registerRoute(Fixtures.open("/p"));
        registry.freeze();
        assertThat(serve(registry).ran()).containsExactly("a", "b", "c");

        registry.beginTeardown();
        Outcome withFilters = serve(registry);
        assertThat(withFilters.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(withFilters.ran()).isEmpty();

        assertThat(registry.unregisterFilter(all.get(1))).isTrue();
        assertThat(registry.filters()).extracting(AdmissionFilter::name).containsExactly("a", "c");
        Outcome afterRemoval = serve(registry);
        assertThat(afterRemoval.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(afterRemoval.ran()).isEmpty();
        assertThat(afterRemoval.answers()).isEqualTo(1);

        registry.unregisterFilter(all.get(0));
        registry.unregisterFilter(all.get(2));
        Outcome empty = serve(registry);
        assertThat(empty.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(empty.ran()).isEmpty();
    }

    @Test
    @DisplayName("while FROZEN, concurrent requests always run the full set in order")
    void frozenRunsFullSetConcurrently() throws Exception {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AdmissionPhase phase = AdmissionPhase.values()[i % 5];
            registry.registerFilter(filter("f" + i, phase));
        }
        registry.filters().forEach(f -> expected.add(f.name()));
        registry.registerRoute(Fixtures.open("/p"));
        registry.freeze();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<String> problems = new ArrayList<>();
                    for (int i = 0; i < 3000; i++) {
                        Outcome outcome = serve(registry);
                        if (outcome.status() != HttpStatus.OK || !outcome.ran().equals(expected)) {
                            problems.add(outcome.toString());
                        }
                    }
                    return problems;
                }));
            }
            for (Future<List<String>> future : futures) {
                assertThat(future.get(2, TimeUnit.MINUTES)).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a request racing beginTeardown and unregisterFilter runs the full set with 200 or no filter with 503, never a partial set")
    void teardownRaceNeverYieldsPartialSet() throws Exception {
        int requesters = 2;
        ExecutorService pool = Executors.newFixedThreadPool(requesters + 1);
        List<String> violations = new CopyOnWriteArrayList<>();
        long full = 0;
        long refused = 0;
        try {
            for (int round = 0; round < 3500; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                List<Shifty> filters = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Shifty f = filter("f" + i, AdmissionPhase.AUTHENTICATION);
                    filters.add(f);
                    registry.registerFilter(f);
                }
                List<String> expected = List.of("f0", "f1", "f2", "f3");
                registry.registerRoute(Fixtures.open("/p"));
                registry.freeze();
                CyclicBarrier start = new CyclicBarrier(requesters + 1);
                List<Future<long[]>> futures = new ArrayList<>();
                for (int t = 0; t < requesters; t++) {
                    futures.add(pool.submit(() -> {
                        long ok = 0;
                        long unavailable = 0;
                        start.await();
                        for (int i = 0; i < 40; i++) {
                            Outcome outcome = serve(registry);
                            if (outcome.status() == HttpStatus.OK && outcome.ran().equals(expected)) {
                                ok++;
                            } else if (outcome.status() == HttpStatus.SERVICE_UNAVAILABLE && outcome.ran().isEmpty()) {
                                unavailable++;
                            } else {
                                violations.add(outcome.toString());
                            }
                        }
                        return new long[] {ok, unavailable};
                    }));
                }
                start.await();
                registry.beginTeardown();
                for (Shifty f : filters) {
                    registry.unregisterFilter(f);
                }
                for (Future<long[]> future : futures) {
                    long[] counts = future.get(1, TimeUnit.MINUTES);
                    full += counts[0];
                    refused += counts[1];
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(violations).isEmpty();
        assertThat(full + refused).isEqualTo(3500L * requesters * 40);
        assertThat(refused).as("the race was exercised: some requests were refused").isPositive();
    }

    @Test
    @DisplayName("a filter registered twice by reference under one name is refused the second time and runs once")
    void sameFilterTwiceRefused() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty one = filter("one", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(one);
        assertThatThrownBy(() -> registry.registerFilter(one)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.filters()).containsExactly(one);
        registry.registerRoute(Fixtures.open("/p"));
        assertThat(serve(registry).ran()).containsExactly("one");
    }

    @Test
    @DisplayName("clear() from another thread while a request is mid-admission does not shorten that request's filter set")
    void clearMidAdmissionLeavesTheRunningRequestWhole() throws Exception {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        registry.registerFilter(new Shifty("a-block", AdmissionPhase.AUTHENTICATION, n -> {
            ran.add(n);
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        registry.registerFilter(new Shifty("b-after", AdmissionPhase.AUTHORIZATION, ran::add));
        AtomicReference<Boolean> handlerRan = new AtomicReference<>(false);
        registry.registerRoute(Fixtures.open("/p", ex -> {
            handlerRan.set(true);
            ex.respond(HttpStatus.OK);
        }));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ScriptedExchange exchange = ScriptedExchange.get("/p");
            Future<?> request = pool.submit(() -> registry.handle(exchange));
            assertThat(inside.await(1, TimeUnit.MINUTES)).isTrue();
            registry.clear();
            release.countDown();
            request.get(1, TimeUnit.MINUTES);
            assertThat(ran).containsExactly("a-block", "b-after");
            assertThat(handlerRan.get()).isTrue();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
            assertThat(registry.filters()).isEmpty();
            assertThat(registry.routeCount()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("clear() during TEARDOWN while a request is mid-admission leaves that request whole")
    void clearDuringTeardownMidAdmission() throws Exception {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        registry.registerFilter(new Shifty("a-block", AdmissionPhase.AUTHENTICATION, n -> {
            ran.add(n);
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        registry.registerFilter(new Shifty("b-after", AdmissionPhase.AUTHORIZATION, ran::add));
        registry.registerRoute(Fixtures.open("/p"));
        registry.freeze();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ScriptedExchange exchange = ScriptedExchange.get("/p");
            Future<?> request = pool.submit(() -> registry.handle(exchange));
            assertThat(inside.await(1, TimeUnit.MINUTES)).isTrue();
            registry.beginTeardown();
            registry.clear();
            release.countDown();
            request.get(1, TimeUnit.MINUTES);
            assertThat(ran).containsExactly("a-block", "b-after");
            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the sort key is the one reported at registration ------------------------------------------------------

    @Test
    @DisplayName("a filter whose name changes after registration keeps its registration-time place when another filter is registered")
    void renamedFilterKeepsItsPlace() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty a = filter("a", AdmissionPhase.AUTHENTICATION);
        Shifty b = filter("b", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(a);
        registry.registerFilter(b);
        a.name = "z";
        registry.registerFilter(filter("c", AdmissionPhase.AUTHENTICATION));
        registry.registerRoute(Fixtures.open("/p"));
        assertThat(serve(registry).ran()).containsExactly("z", "b", "c");
    }

    @Test
    @DisplayName("a filter whose phase changes after registration cannot jump the queue when another filter is registered")
    void rephasedFilterCannotJumpTheQueue() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty early = filter("early", AdmissionPhase.AUTHENTICATION);
        Shifty late = filter("late", AdmissionPhase.POST_ADMISSION);
        registry.registerFilter(early);
        registry.registerFilter(late);
        late.phase = AdmissionPhase.PRE_ADMISSION;
        registry.registerFilter(filter("other", AdmissionPhase.AUTHORIZATION));
        registry.registerRoute(Fixtures.open("/p"));
        assertThat(serve(registry).ran()).containsExactly("early", "other", "late");
    }

    @Test
    @DisplayName("a filter renamed after registration cannot free its old name for a duplicate")
    void renamedFilterStillHoldsItsRegisteredName() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty first = filter("auth", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(first);
        first.name = "auth-renamed";
        assertThatThrownBy(() -> registry.registerFilter(filter("auth", AdmissionPhase.AUTHENTICATION)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a registration that throws leaves no filter behind for a later registration to publish")
    void failedRegistrationLeavesNothingBehind() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        Shifty first = filter("first", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(first);
        first.name = null;
        Shifty doomed = filter("doomed", AdmissionPhase.AUTHENTICATION);
        boolean registered;
        try {
            registry.registerFilter(doomed);
            registered = true;
        } catch (RuntimeException expected) {
            registered = false;
        }
        first.name = "first";
        registry.registerFilter(filter("healthy", AdmissionPhase.AUTHENTICATION));
        if (registered) {
            assertThat(registry.filters()).contains(doomed);
        } else {
            assertThat(registry.filters()).as("a filter whose registration threw").doesNotContain(doomed);
        }
    }
}
