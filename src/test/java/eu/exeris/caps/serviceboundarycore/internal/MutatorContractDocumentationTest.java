package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.CountingHandler;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ProbeFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ThrowingLog;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static eu.exeris.caps.serviceboundarycore.testsupport.Identity.assertSameInstances;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The class documentation of the registry says what a mutator does when the record it writes fails, and the
 * documentation of {@code ApiSurfaceRegistry.unregisterFilter} says how a filter is identified. Each sentence is
 * pinned to the source text and exercised.
 */
@DisplayName("Documentation as contract: mutators, the record they write, and how a removal identifies its target")
class MutatorContractDocumentationTest {

    private static final String REGISTRY =
            "src/main/java/eu/exeris/caps/serviceboundarycore/internal/DefaultApiSurfaceRegistry.java";
    private static final String API =
            "src/main/java/eu/exeris/caps/serviceboundarycore/api/ApiSurfaceRegistry.java";

    private static String prose(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("(?m)^\\s*(/\\*\\*|\\*/|\\*)[ \\t]?", " ").replaceAll("\\s+", " ");
    }

    private static ProbeFilter filter(String name) {
        return new ProbeFilter(name, AdmissionPhase.AUTHENTICATION, new ArrayList<>());
    }

    private static final class Snapshot {
        final List<AdmissionFilter> filters;
        final List<ApiSurfaceRoute> routes;
        final boolean frozen;
        final boolean tearingDown;

        Snapshot(DefaultApiSurfaceRegistry registry) {
            filters = registry.filters();
            routes = registry.routes();
            frozen = registry.isFrozen();
            tearingDown = registry.isTearingDown();
        }

        void assertUnchanged(DefaultApiSurfaceRegistry registry, String what) {
            assertSameInstances(registry.filters(), filters.toArray());
            assertThat(registry.routes()).as(what).containsExactlyElementsOf(routes);
            assertThat(registry.isFrozen()).as(what).isEqualTo(frozen);
            assertThat(registry.isTearingDown()).as(what).isEqualTo(tearingDown);
        }
    }

    @Test
    @DisplayName("\"Every mutator ... either throws and leaves the registry as it was, or commits and returns normally\"")
    void everyMutatorThrowsAndChangesNothingOrCommits() throws IOException {
        assertThat(prose(REGISTRY))
                .contains("A change that has been made is not undone by a failure to log it.")
                .contains("Every mutator ({@code registerFilter}, {@code unregisterFilter}, {@code registerRoute}, "
                        + "{@code unregisterRoute}, {@code clear}, {@code freeze}, {@code beginTeardown}) either throws "
                        + "and leaves the registry as it was, or commits and returns normally.");

        ProbeFilter present = filter("present");
        ApiSurfaceRoute route = Fixtures.open("/r");
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerFilter(present);
        registry.registerRoute(route);

        // refusals while open
        Snapshot open = new Snapshot(registry);
        assertThatThrownBy(() -> registry.registerFilter(present)).isInstanceOf(IllegalStateException.class);
        open.assertUnchanged(registry, "duplicate instance");
        assertThatThrownBy(() -> registry.registerFilter(filter("present"))).isInstanceOf(IllegalStateException.class);
        open.assertUnchanged(registry, "duplicate name");
        assertThatThrownBy(() -> registry.registerFilter(filter(" "))).isInstanceOf(IllegalArgumentException.class);
        open.assertUnchanged(registry, "blank name");
        assertThatThrownBy(() -> registry.registerRoute(Fixtures.open("/r"))).isInstanceOf(IllegalStateException.class);
        open.assertUnchanged(registry, "duplicate route");
        assertThatThrownBy(() -> registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/n", "N", List.of(),
                Fixtures.ok()))).isInstanceOf(IllegalStateException.class);
        open.assertUnchanged(registry, "strict mode");
        assertThatThrownBy(() -> registry.registerFilter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.registerRoute(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.unregisterFilter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.unregisterRoute(null)).isInstanceOf(NullPointerException.class);
        open.assertUnchanged(registry, "null arguments");

        // refusals while frozen: every mutator but the transitions
        registry.freeze();
        Snapshot frozen = new Snapshot(registry);
        assertThatThrownBy(() -> registry.registerFilter(filter("late"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerRoute(Fixtures.open("/late"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.unregisterFilter(present)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.unregisterRoute(route)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::clear).isInstanceOf(IllegalStateException.class);
        frozen.assertUnchanged(registry, "frozen");

        // refusal of freeze after teardown, and the commits that follow
        registry.beginTeardown();
        Snapshot tearing = new Snapshot(registry);
        assertThatThrownBy(registry::freeze).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerFilter(filter("late"))).isInstanceOf(IllegalStateException.class);
        tearing.assertUnchanged(registry, "teardown refusals");
        assertThat(registry.unregisterFilter(present)).isTrue();
        assertThat(registry.unregisterRoute(route)).isTrue();
        registry.clear();
        assertThat(registry.filters()).isEmpty();
        assertThat(registry.routeCount()).isZero();
    }

    @Test
    @DisplayName("\"A log record is written after the lock is released and after the commit\"")
    void recordIsWrittenAfterCommitAndAfterTheLockIsReleased() throws Exception {
        assertThat(prose(REGISTRY)).contains("A log record is written after the lock is released and after the commit;");

        for (boolean filterRegistration : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            ProbeFilter added = filter("added");
            ApiSurfaceRoute addedRoute = Fixtures.open("/added");
            AtomicBoolean committedWhenWritten = new AtomicBoolean();
            AtomicBoolean lockFreeWhenWritten = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (ThrowingLog log = new ThrowingLog(DefaultApiSurfaceRegistry.class, record -> {
                try {
                    committedWhenWritten.set(filterRegistration
                            ? registry.filters().size() == 1 && registry.filters().get(0) == added
                            : registry.routes().contains(addedRoute));
                    // another thread takes the lock from inside the record: it would wait forever if it were held
                    pool.submit(() -> registry.beginTeardown()).get(10, TimeUnit.SECONDS);
                    lockFreeWhenWritten.set(true);
                } catch (Throwable t) {
                    failure.set(t);
                }
            })) {
                if (filterRegistration) {
                    registry.registerFilter(added);
                } else {
                    registry.registerRoute(addedRoute);
                }
                assertThat(log.published()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            assertThat(failure.get()).isNull();
            assertThat(committedWhenWritten.get()).as("the change was visible when the record was written").isTrue();
            assertThat(lockFreeWhenWritten.get()).as("another thread could take the lock while the record was written")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("\"a failure to write it is diagnostics lost and is swallowed (a VirtualMachineError is not)\"")
    void failureToLogIsSwallowedExceptAVirtualMachineError() throws IOException {
        assertThat(prose(REGISTRY)).contains("a failure to write it is diagnostics lost and is swallowed "
                + "(a {@link VirtualMachineError} is not),");

        for (Throwable failure : List.<Throwable>of(new IllegalStateException("x"), new AssertionError("y"),
                new StackOverflowError(), new OutOfMemoryError("z"))) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            ProbeFilter f = filter("f");
            try (ThrowingLog log = ThrowingLog.throwing(DefaultApiSurfaceRegistry.class, failure)) {
                if (failure instanceof VirtualMachineError) {
                    assertThatThrownBy(() -> registry.registerFilter(f)).isSameAs(failure);
                } else {
                    registry.registerFilter(f);
                }
            }
            assertSameInstances(registry.filters(), f);
        }
    }

    @Test
    @DisplayName("\"and nothing else runs between the commit and the return\"")
    void nothingElseRunsBetweenTheCommitAndTheReturn() throws IOException {
        assertThat(prose(REGISTRY)).contains("and nothing else runs between the commit and the return.");

        ProbeFilter f = filter("f");
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        int[] atRecord = new int[6];
        try (ThrowingLog log = new ThrowingLog(DefaultApiSurfaceRegistry.class, record -> {
            atRecord[0] = f.nameCalls.get();
            atRecord[1] = f.phaseCalls.get();
            atRecord[2] = f.equalsCalls.get();
            atRecord[3] = f.hashCodeCalls.get();
            atRecord[4] = f.toStringCalls.get();
            atRecord[5] = f.filterCalls.get();
        })) {
            registry.registerFilter(f);
            assertThat(log.published()).isEqualTo(1);
        }

        assertThat(new int[] {f.nameCalls.get(), f.phaseCalls.get(), f.equalsCalls.get(), f.hashCodeCalls.get(),
                f.toStringCalls.get(), f.filterCalls.get()})
                .as("no call on the filter between the record and the return")
                .containsExactly(atRecord);
        assertThat(atRecord).as("name() and phase() ran once, before the commit").containsExactly(1, 1, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("\"No code supplied by a caller runs under mutationLock: a filter is identified by reference (==) ...\"")
    void filterIsIdentifiedByReference() throws IOException {
        assertThat(prose(REGISTRY)).contains("No code supplied by a caller runs under {@code mutationLock}: a filter is "
                + "identified by reference ({@code ==}), so {@code equals}, {@code hashCode} and {@code toString} of a "
                + "filter are never called by the registry, and a filter is removed by passing the same instance that "
                + "was registered.");

        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ProbeFilter registered = filter("registered").equalToEvery(AdmissionFilter.class);
        ProbeFilter equalInstance = filter("equal").equalToEvery(AdmissionFilter.class);
        registry.registerFilter(registered);

        assertThat(registry.unregisterFilter(equalInstance)).isFalse();
        assertThat(registry.unregisterFilter(registered)).isTrue();

        assertThat(registered.objectMethodCalls() + equalInstance.objectMethodCalls()).isZero();
    }

    @Test
    @DisplayName("\"A route is removed when it equals the given one ... and its handler is the same instance\"")
    void routeIsRemovedWhenEqualAndSharingTheHandler() throws IOException {
        assertThat(prose(REGISTRY)).contains("A route is removed when it equals the given one, which compares its "
                + "method, path pattern and action name (library types only), and its handler is the same instance.");

        CountingHandler handler = new CountingHandler();
        CountingHandler other = new CountingHandler();
        List<RouteRequirement> open = List.of(RouteRequirement.permitAll());
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#get", open, handler));

        assertThat(registry.unregisterRoute(new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#get", open, other))).isFalse();
        assertThat(registry.unregisterRoute(new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#renamed", open, handler)))
                .isFalse();
        assertThat(registry.unregisterRoute(new ApiSurfaceRoute(HttpMethod.GET, "/a", "A#get", open, handler))).isTrue();

        assertThat(handler.objectMethodCalls() + other.objectMethodCalls()).isZero();
    }

    @Test
    @DisplayName("ApiSurfaceRegistry.unregisterFilter: identified by reference; an equal instance removes nothing; "
            + "equals, hashCode and toString are never called")
    void apiDocumentationOfUnregisterFilter() throws IOException {
        assertThat(prose(API)).contains("Removes a previously registered admission filter. A filter is identified by "
                + "reference: pass the same instance that was registered. An instance that is equal to it but is not "
                + "the same one removes nothing, and the registry never calls {@code equals}, {@code hashCode} or "
                + "{@code toString} of a filter.");
        assertThat(prose(API)).contains("@param filter the filter to remove, the instance that was registered; never "
                + "{@code null}")
                .contains("@return {@code true} if the filter was removed, {@code false} otherwise");

        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ProbeFilter registered = filter("registered");
        ProbeFilter equalOne = filter("equal").equalToEvery(AdmissionFilter.class);
        registry.registerFilter(registered);

        assertThat(registry.unregisterFilter(equalOne)).isFalse();
        assertSameInstances(registry.filters(), registered);
        assertThat(registry.unregisterFilter(registered)).isTrue();
        assertThat(registry.unregisterFilter(registered)).isFalse();
        assertThat(registered.objectMethodCalls() + equalOne.objectMethodCalls()).isZero();
    }
}
