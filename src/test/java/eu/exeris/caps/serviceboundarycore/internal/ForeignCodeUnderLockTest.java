package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.CountingHandler;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.ProbeFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No code supplied by a caller runs while the registry holds its mutation lock. A filter is identified by
 * reference and a route by its own library-type fields plus the identity of its handler, so the registry never
 * calls {@code equals}, {@code hashCode} or {@code toString} of a filter or of a route handler, and it reads a
 * filter's {@code name()} and {@code phase()} exactly once per registration attempt, before it takes the lock.
 * The only foreign code that can therefore park a thread inside a mutator is that one read, which holds nothing.
 */
@DisplayName("Foreign code never runs under the mutation lock")
class ForeignCodeUnderLockTest {

    private static final String FROZEN = "The registry is frozen and can no longer be modified";

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private DefaultApiSurfaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/p"));
    }

    private ProbeFilter probe(String name) {
        return new ProbeFilter(name, AdmissionPhase.AUTHENTICATION, trace)
                .equalsRule(other -> { throw new AssertionError("equals must not be called"); });
    }

    /** Counts a registration attempt against the filter it was made with. */
    private static void attempt(Map<ProbeFilter, Integer> attempts, ProbeFilter filter) {
        attempts.merge(filter, 1, Integer::sum);
    }

    // ---- filters --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("through every mutator, refusal and reader, a filter's equals, hashCode and toString are never called "
            + "and name() and phase() are called once per registration attempt")
    void filterIsNeverAskedAnythingUnderTheLock() {
        // an identity map: ProbeFilter counts equals and hashCode, so it must not be used as a hash key
        Map<ProbeFilter, Integer> attempts = new java.util.IdentityHashMap<>();
        List<ProbeFilter> all = new ArrayList<>();
        try (LogCapture log = new LogCapture(DefaultApiSurfaceRegistry.class)) {
            ProbeFilter good = probe("good");
            ProbeFilter duplicateName = probe("good");
            ProbeFilter blank = probe(" ");
            ProbeFilter nullPhase = new ProbeFilter("null-phase", null, trace);
            ProbeFilter second = probe("second");
            ProbeFilter twin = probe("twin");
            ProbeFilter absent = probe("absent");
            ProbeFilter late = probe("late");
            ProbeFilter kept = probe("kept");
            all.addAll(List.of(good, duplicateName, blank, nullPhase, second, twin, absent, late, kept));

            registry.registerFilter(good);
            attempt(attempts, good);
            assertThatThrownBy(() -> registry.registerFilter(good)).isInstanceOf(IllegalStateException.class);
            attempt(attempts, good);
            assertThatThrownBy(() -> registry.registerFilter(duplicateName)).isInstanceOf(IllegalStateException.class);
            attempt(attempts, duplicateName);
            assertThatThrownBy(() -> registry.registerFilter(blank)).isInstanceOf(IllegalArgumentException.class);
            attempt(attempts, blank);
            assertThatThrownBy(() -> registry.registerFilter(nullPhase)).isInstanceOf(IllegalArgumentException.class);
            attempt(attempts, nullPhase);
            registry.registerFilter(second);
            attempt(attempts, second);

            assertThat(registry.unregisterFilter(second)).isTrue();
            assertThat(registry.unregisterFilter(twin)).isFalse();
            assertThat(registry.unregisterFilter(absent)).isFalse();
            registry.filters();
            registry.routes();
            registry.clear();

            registry.registerFilter(kept);
            attempt(attempts, kept);
            registry.freeze();
            assertThatThrownBy(() -> registry.registerFilter(late)).isInstanceOf(IllegalStateException.class)
                    .hasMessage(FROZEN);
            attempt(attempts, late);
            assertThatThrownBy(() -> registry.unregisterFilter(kept)).isInstanceOf(IllegalStateException.class);
            registry.filters();
            registry.beginTeardown();
            assertThat(registry.unregisterFilter(kept)).isTrue();
            registry.clear();

            // forces the formatting of every record, as a handler that renders them would
            for (var record : log.at(Level.FINE)) {
                LogCapture.everything(record);
            }
            assertThat(log.at(Level.FINE)).as("the registration records were produced").isNotEmpty();
        }

        for (ProbeFilter filter : all) {
            int expected = attempts.getOrDefault(filter, 0);
            String label = filter.label();
            assertThat(filter.equalsCalls.get()).as("equals of %s", label).isZero();
            assertThat(filter.hashCodeCalls.get()).as("hashCode of %s", label).isZero();
            assertThat(filter.toStringCalls.get()).as("toString of %s", label).isZero();
            assertThat(filter.nameCalls.get()).as("name() of %s", label).isEqualTo(expected);
            assertThat(filter.phaseCalls.get()).as("phase() of %s", label).isEqualTo(expected);
            assertThat(filter.filterCalls.get()).as("no request ran %s", label).isZero();
        }
    }

    @Test
    @DisplayName("serving requests runs filter() once per request and still never calls equals, hashCode or toString")
    void servingCallsNoObjectMethods() {
        ProbeFilter first = probe("first");
        ProbeFilter second = probe("second");
        registry.registerFilter(first);
        registry.registerFilter(second);
        registry.freeze();

        registry.handle(ScriptedExchange.get("/p"));
        registry.handle(ScriptedExchange.get("/p"));
        registry.beginTeardown();
        registry.handle(ScriptedExchange.get("/p"));

        for (ProbeFilter filter : List.of(first, second)) {
            assertThat(filter.filterCalls.get()).isEqualTo(2);
            assertThat(filter.objectMethodCalls()).isZero();
            assertThat(filter.nameCalls.get()).as("name() is read at registration only").isEqualTo(1);
            assertThat(filter.phaseCalls.get()).as("phase() is read at registration only").isEqualTo(1);
        }
    }

    // ---- route handlers -------------------------------------------------------------------------------------

    @Test
    @DisplayName("through every route mutator, refusal and reader, a handler is never called and neither are its "
            + "equals, hashCode and toString; serving calls only handle")
    void handlerIsNeverAskedAnythingUnderTheLock() {
        CountingHandler first = new CountingHandler();
        CountingHandler second = new CountingHandler();
        List<RouteRequirement> open = List.of(RouteRequirement.permitAll());
        ApiSurfaceRoute a = new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#get", open, first);
        ApiSurfaceRoute sameShape = new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#other", open, first);
        ApiSurfaceRoute noRequirement = new ApiSurfaceRoute(HttpMethod.PUT, "/c", "C#put", List.of(), second);
        ApiSurfaceRoute b = new ApiSurfaceRoute(HttpMethod.POST, "/b/{id}", "B#post", open, second);
        ApiSurfaceRoute equalDifferentHandler = new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#get", open, second);
        ApiSurfaceRoute absent = new ApiSurfaceRoute(HttpMethod.GET, "/absent", "Absent#get", open, second);
        ApiSurfaceRoute late = new ApiSurfaceRoute(HttpMethod.GET, "/late", "Late#get", open, first);

        try (LogCapture log = new LogCapture(DefaultApiSurfaceRegistry.class)) {
            registry.registerRoute(a);
            assertThatThrownBy(() -> registry.registerRoute(sameShape)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> registry.registerRoute(noRequirement)).isInstanceOf(IllegalStateException.class);
            registry.registerRoute(b);
            assertThat(registry.unregisterRoute(equalDifferentHandler)).isFalse();
            assertThat(registry.unregisterRoute(absent)).isFalse();
            assertThat(registry.unregisterRoute(a)).isTrue();
            registry.routes();
            registry.routeCount();
            registry.clear();
            registry.registerRoute(a);
            registry.freeze();
            assertThatThrownBy(() -> registry.registerRoute(late)).isInstanceOf(IllegalStateException.class)
                    .hasMessage(FROZEN);
            assertThatThrownBy(() -> registry.unregisterRoute(a)).isInstanceOf(IllegalStateException.class);
            registry.beginTeardown();
            assertThat(registry.unregisterRoute(a)).isTrue();
            registry.clear();
            for (var record : log.at(Level.FINE)) {
                LogCapture.everything(record);
            }
            assertThat(log.at(Level.FINE)).isNotEmpty();
        }

        assertThat(first.handleCalls.get() + second.handleCalls.get()).as("no handler ran").isZero();
        assertThat(first.objectMethodCalls()).as("equals, hashCode, toString of the first handler").isZero();
        assertThat(second.objectMethodCalls()).as("equals, hashCode, toString of the second handler").isZero();
    }

    @Test
    @DisplayName("a request calls the handler's handle once and none of its equals, hashCode or toString")
    void servingCallsOnlyHandle() {
        CountingHandler handler = new CountingHandler();
        DefaultApiSurfaceRegistry local = new DefaultApiSurfaceRegistry();
        local.registerRoute(Fixtures.open("/h", handler));
        local.freeze();

        local.handle(ScriptedExchange.get("/h"));
        local.handle(ScriptedExchange.get("/h?x=1"));
        local.handle(ScriptedExchange.get("/missing"));

        assertThat(handler.handleCalls.get()).isEqualTo(2);
        assertThat(handler.objectMethodCalls()).isZero();
    }

    // ---- structure: foreign code cannot hold the lock --------------------------------------------------------

    /** Parks a registration inside the filter's own name() or phase(), the one place foreign code still runs. */
    private void foreignCodeCannotHoldTheLock(boolean parkInName, boolean teardown) throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable park = () -> {
            parked.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        ProbeFilter parker = probe("parker");
        if (parkInName) {
            parker.onName(park);
        } else {
            parker.onPhase(park);
        }
        ProbeFilter resident = probe("resident");
        registry.registerFilter(resident);
        ProbeFilter other = probe("other");
        ApiSurfaceRoute extra = Fixtures.open("/extra");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> registering = pool.submit(() -> registry.registerFilter(parker));
            assertThat(parked.await(10, TimeUnit.SECONDS)).as("the registration reached name()/phase()").isTrue();

            Future<Map<String, Object>> others = pool.submit(() -> {
                Map<String, Object> results = new LinkedHashMap<>();
                registry.registerFilter(other);
                registry.registerRoute(extra);
                results.put("unregisterRoute", registry.unregisterRoute(extra));
                results.put("unregisterFilter", registry.unregisterFilter(other));
                registry.filters();
                registry.routes();
                registry.clear();
                if (teardown) {
                    registry.beginTeardown();
                } else {
                    registry.freeze();
                }
                results.put("serving", registry.isFrozen());
                return results;
            });
            Map<String, Object> results = others.get(10, TimeUnit.SECONDS);

            assertThat(registering.isDone()).as("the first mutator is still parked while the others completed").isFalse();
            assertThat(results).containsEntry("unregisterRoute", true).containsEntry("unregisterFilter", true)
                    .containsEntry("serving", true);
            assertThat(registry.isTearingDown()).isEqualTo(teardown);

            release.countDown();
            assertThatThrownBy(() -> registering.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(parker.nameCalls.get()).isEqualTo(1);
        assertThat(parker.phaseCalls.get()).isEqualTo(1);
        assertThat(parker.objectMethodCalls()).isZero();
        assertThat(registry.filters()).as("clear emptied the registry and the parked registration was refused").isEmpty();
        assertThat(registry.routeCount()).isZero();
    }

    @Test
    @DisplayName("a registration parked in the filter's name() does not hold up any other mutator or freeze")
    void parkedInNameHoldsNothing() throws Exception {
        foreignCodeCannotHoldTheLock(true, false);
    }

    @Test
    @DisplayName("a registration parked in the filter's phase() does not hold up any other mutator or freeze")
    void parkedInPhaseHoldsNothing() throws Exception {
        foreignCodeCannotHoldTheLock(false, false);
    }

    @Test
    @DisplayName("a registration parked in the filter's name() does not hold up any other mutator or beginTeardown")
    void parkedInNameHoldsNothingBeforeTeardown() throws Exception {
        foreignCodeCannotHoldTheLock(true, true);
    }
}
