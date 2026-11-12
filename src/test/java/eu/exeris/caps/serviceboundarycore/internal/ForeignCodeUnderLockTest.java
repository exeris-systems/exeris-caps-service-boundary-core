package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ProbeFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The only code of a filter, route or handler that runs while the registry holds its mutation lock is the
 * {@code equals} of a registered filter, called by {@code unregisterFilter}. Registration, route removal and
 * {@code clear} call nothing that a caller supplies: a route is a final class that is compared by its own
 * fields and by the identity of its handler, and a filter is compared by reference, never by {@code equals}.
 * Whatever that one call does, it must not leave the registry reporting a set that requests do not run.
 */
@DisplayName("Foreign code under the mutation lock")
class ForeignCodeUnderLockTest {

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private DefaultApiSurfaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/p"));
    }

    private List<String> serve() {
        trace.clear();
        registry.handle(ScriptedExchange.get("/p"));
        return List.copyOf(trace);
    }

    private ProbeFilter probe(String name) {
        return new ProbeFilter(name, AdmissionPhase.AUTHENTICATION, trace);
    }

    private List<String> reported() {
        return registry.filters().stream().map(f -> ((ProbeFilter) f).toString().replaceAll("ProbeFilter\\[(.*)]", "$1"))
                .toList();
    }

    private void assertSelfConsistent() {
        assertThat(serve()).as("what a request runs equals what filters() reports").isEqualTo(reported());
    }

    private static void blockedWithin(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("thread never blocked on the lock").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    /** A handler on which every Object method and the handler method itself throws and is counted. */
    private static final class HostileHandler implements HttpHandler {
        final AtomicInteger calls = new AtomicInteger();

        @Override public void handle(HttpExchange exchange) { calls.incrementAndGet(); throw new AssertionError("handle"); }

        @Override public boolean equals(Object other) { calls.incrementAndGet(); throw new AssertionError("equals"); }

        @Override public int hashCode() { calls.incrementAndGet(); throw new AssertionError("hashCode"); }

        @Override public String toString() { calls.incrementAndGet(); throw new AssertionError("toString"); }
    }

    private static final class HostileFilter implements AdmissionFilter {
        final AtomicInteger calls = new AtomicInteger();
        private final String name;

        HostileFilter(String name) { this.name = name; }

        @Override public String name() { return name; }

        @Override public AdmissionPhase phase() { return AdmissionPhase.AUTHENTICATION; }

        @Override public boolean filter(HttpExchange exchange, eu.exeris.caps.serviceboundarycore.api.RouteMatch m) {
            return true;
        }

        @Override public boolean equals(Object other) { calls.incrementAndGet(); throw new AssertionError("equals"); }

        @Override public int hashCode() { calls.incrementAndGet(); throw new AssertionError("hashCode"); }

        @Override public String toString() { calls.incrementAndGet(); throw new AssertionError("toString"); }
    }

    // ---- what never runs under the lock --------------------------------------------------------------------

    @Test
    @DisplayName("registering, removing and clearing routes never call the handler, its equals, hashCode or toString")
    void routeMutatorsCallNothingOfTheCaller() {
        HostileHandler handler = new HostileHandler();
        ApiSurfaceRoute route = new ApiSurfaceRoute(HttpMethod.POST, "/hostile/{id}", "Hostile#post",
                List.of(RouteRequirement.permitAll()), handler);
        ApiSurfaceRoute sameShape = new ApiSurfaceRoute(HttpMethod.POST, "/hostile/{other}", "Hostile#other",
                List.of(RouteRequirement.permitAll()), handler);

        registry.registerRoute(route);
        assertThatThrownBy(() -> registry.registerRoute(sameShape)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.routeCount()).isEqualTo(2);
        assertThat(registry.unregisterRoute(sameShape)).isFalse();
        assertThat(registry.unregisterRoute(route)).isTrue();
        registry.registerRoute(route);
        registry.freeze();
        registry.beginTeardown();
        assertThat(registry.unregisterRoute(route)).isTrue();
        registry.clear();

        assertThat(handler.calls.get()).isZero();
    }

    @Test
    @DisplayName("registering, listing, serving, freezing and clearing never call a filter's equals, hashCode or toString")
    void filterMutatorsExceptUnregisterCallNothingOfTheCaller() {
        HostileFilter first = new HostileFilter("first");
        HostileFilter second = new HostileFilter("second");

        registry.registerFilter(first);
        registry.registerFilter(second);
        assertThatThrownBy(() -> registry.registerFilter(first)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerFilter(new HostileFilter("first")))
                .isInstanceOf(IllegalStateException.class);
        registry.filters();
        registry.handle(ScriptedExchange.get("/p"));
        registry.freeze();
        registry.handle(ScriptedExchange.get("/p"));
        registry.beginTeardown();
        registry.handle(ScriptedExchange.get("/p"));
        registry.clear();

        assertThat(first.calls.get() + second.calls.get()).isZero();
    }

    // ---- the one call that runs under the lock -------------------------------------------------------------

    @Test
    @DisplayName("an equals that throws, called for a filter that is not the target or for the target, leaves registry and requests as they were")
    void throwFromEqualsChangesNothing() {
        ProbeFilter thrower = probe("b-thrower").equalsRule(o -> { throw new IllegalStateException("equals"); });
        registry.registerFilter(probe("a-first"));
        registry.registerFilter(thrower);
        registry.registerFilter(probe("c-last"));
        List<AdmissionFilter> before = registry.filters();
        List<String> ranBefore = serve();

        assertThatThrownBy(() -> registry.unregisterFilter(probe("target"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.unregisterFilter(thrower)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.filters()).isEqualTo(before);
        assertThat(serve()).isEqualTo(ranBefore);
        registry.registerFilter(probe("d-after"));
        registry.clear();
        assertThat(registry.filters()).isEmpty();
    }

    @Test
    @DisplayName("an equals that blocks holds every mutator, route mutators included, and never blocks a request")
    void blockingEqualsBlocksMutatorsNotRequests() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ProbeFilter blocker = probe("a-blocker").onEquals(() -> {
            if (first.getAndSet(false)) {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ProbeFilter target = probe("b-target");
        registry.registerFilter(blocker);
        registry.registerFilter(target);
        ApiSurfaceRoute extra = Fixtures.open("/extra");

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Thread registering = new Thread(() -> registry.registerFilter(probe("c-late")));
        Thread routing = new Thread(() -> registry.registerRoute(extra));
        Thread freezing = new Thread(registry::freeze);
        try {
            Future<Boolean> removing = pool.submit(() -> registry.unregisterFilter(target));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(serve()).as("a request is served while the lock is held").containsExactly("a-blocker", "b-target");
            registering.start();
            routing.start();
            freezing.start();
            blockedWithin(registering);
            blockedWithin(routing);
            blockedWithin(freezing);
            assertThat(registry.isFrozen()).isFalse();
            assertThat(registry.routeCount()).isEqualTo(1);

            release.countDown();
            assertThat(removing.get(10, TimeUnit.SECONDS)).isTrue();
            registering.join(10_000);
            routing.join(10_000);
            freezing.join(10_000);
            assertThat(registering.isAlive() || routing.isAlive() || freezing.isAlive()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertSelfConsistent();
        assertThat(registry.isFrozen()).isTrue();
    }

    @Test
    @DisplayName("an equals that registers a filter on the same thread completes, and the registry stays consistent")
    void reentrantRegistrationFromEqualsFalse() {
        AtomicBoolean once = new AtomicBoolean(true);
        ProbeFilter a = probe("a");
        a.onEquals(() -> {
            if (once.getAndSet(false)) {
                registry.registerFilter(probe("c-reentrant"));
            }
        });
        registry.registerFilter(a);
        registry.registerFilter(probe("b"));

        assertThat(registry.unregisterFilter(probe("stranger"))).isFalse();

        assertThat(reported()).containsExactly("a", "b", "c-reentrant");
        assertSelfConsistent();
    }

    @Test
    @DisplayName("an equals that registers a filter and then claims equality removes itself and keeps the new filter")
    void reentrantRegistrationFromEqualsTrue() {
        AtomicBoolean once = new AtomicBoolean(true);
        ProbeFilter a = probe("a").equalToEvery(AdmissionFilter.class);
        a.onEquals(() -> {
            if (once.getAndSet(false)) {
                registry.registerFilter(probe("c-reentrant"));
            }
        });
        registry.registerFilter(a);
        registry.registerFilter(probe("b"));

        assertThat(registry.unregisterFilter(probe("stranger"))).isTrue();

        assertThat(reported()).containsExactly("b", "c-reentrant");
        assertSelfConsistent();
    }

    @Test
    @DisplayName("an equals that removes another filter on the same thread leaves the registry reporting what requests run")
    void reentrantRemovalFromEqualsStaysConsistent() {
        for (boolean claimsEquality : new boolean[] {false, true}) {
            DefaultApiSurfaceRegistry local = new DefaultApiSurfaceRegistry();
            local.registerRoute(Fixtures.open("/p"));
            registry = local;
            ProbeFilter a = probe("a");
            ProbeFilter b = probe("b");
            ProbeFilter c = probe("c");
            AtomicBoolean once = new AtomicBoolean(true);
            b.onEquals(() -> {
                if (once.getAndSet(false)) {
                    local.unregisterFilter(a);
                }
            });
            b.equalsRule(o -> claimsEquality && o instanceof ProbeFilter);
            local.registerFilter(a);
            local.registerFilter(b);
            local.registerFilter(c);

            local.unregisterFilter(c);

            assertSelfConsistent();
        }
    }

    @Test
    @DisplayName("an equals that freezes the registry on the same thread leaves the registry reporting what requests run")
    void reentrantFreezeFromEqualsStaysConsistent() {
        ProbeFilter a = probe("a").equalToEvery(AdmissionFilter.class);
        a.onEquals(registry::freeze);
        registry.registerFilter(a);
        registry.registerFilter(probe("b"));

        registry.unregisterFilter(probe("stranger"));

        assertThat(registry.isFrozen()).isTrue();
        assertSelfConsistent();
    }

    @Test
    @DisplayName("handlers are untouched by a failing filter equals: a route registered afterwards still serves")
    void routesUnaffectedByFailedUnregistration() {
        registry.registerFilter(probe("a").equalsRule(o -> { throw new IllegalStateException("equals"); }));
        assertThatThrownBy(() -> registry.unregisterFilter(probe("x"))).isInstanceOf(IllegalStateException.class);
        AtomicInteger handled = new AtomicInteger();
        registry.registerRoute(Fixtures.open("/q", ex -> {
            handled.incrementAndGet();
            ex.respond(HttpStatus.OK);
        }));

        registry.handle(ScriptedExchange.get("/q"));

        assertThat(handled.get()).isEqualTo(1);
    }
}
