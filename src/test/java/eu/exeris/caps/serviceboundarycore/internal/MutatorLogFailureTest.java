package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ProbeFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.ThrowingLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static eu.exeris.caps.serviceboundarycore.testsupport.Identity.assertSameInstances;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A mutator either throws and leaves the registry as it was, or commits and returns normally. The record it
 * writes afterwards is diagnostics: a handler that fails must not turn a committed change into a thrown one,
 * whatever the handler throws, except a {@link VirtualMachineError}, which propagates.
 */
@DisplayName("Mutators under a log handler that throws")
class MutatorLogFailureTest {

    static final List<String> MUTATORS = List.of("registerFilter", "unregisterFilter", "registerRoute",
            "unregisterRoute", "clear", "freeze", "beginTeardown");
    private static final Set<String> WRITE_A_RECORD = Set.of("registerFilter", "registerRoute");

    /** One mutator: how to prepare the registry, the call, and what the registry shows once it committed. */
    record Scenario(Consumer<DefaultApiSurfaceRegistry> arrange, Consumer<DefaultApiSurfaceRegistry> act,
                    Consumer<DefaultApiSurfaceRegistry> verifyCommitted) { }

    static Scenario scenario(String mutator) {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        ProbeFilter filter = new ProbeFilter("f", AdmissionPhase.AUTHENTICATION, trace);
        ApiSurfaceRoute route = Fixtures.open("/r");
        Consumer<DefaultApiSurfaceRegistry> withBase = reg -> reg.registerRoute(Fixtures.open("/p"));
        return switch (mutator) {
            case "registerFilter" -> new Scenario(withBase, reg -> reg.registerFilter(filter), reg -> {
                assertSameInstances(reg.filters(), filter);
                trace.clear();
                reg.handle(ScriptedExchange.get("/p"));
                assertThat(trace).as("requests run the registered filter").containsExactly("f");
            });
            case "unregisterFilter" -> new Scenario(reg -> { withBase.accept(reg); reg.registerFilter(filter); },
                    reg -> assertThat(reg.unregisterFilter(filter)).isTrue(), reg -> {
                assertThat(reg.filters()).isEmpty();
                trace.clear();
                reg.handle(ScriptedExchange.get("/p"));
                assertThat(trace).as("requests no longer run the removed filter").isEmpty();
            });
            case "registerRoute" -> new Scenario(withBase, reg -> reg.registerRoute(route), reg -> {
                assertThat(reg.routes()).contains(route);
                ScriptedExchange exchange = ScriptedExchange.get("/r");
                reg.handle(exchange);
                assertThat(exchange.statuses()).containsExactly(eu.exeris.kernel.spi.http.HttpStatus.OK);
            });
            case "unregisterRoute" -> new Scenario(reg -> { withBase.accept(reg); reg.registerRoute(route); },
                    reg -> assertThat(reg.unregisterRoute(route)).isTrue(), reg -> {
                assertThat(reg.routes()).doesNotContain(route);
                ScriptedExchange exchange = ScriptedExchange.get("/r");
                reg.handle(exchange);
                assertThat(exchange.statuses()).containsExactly(eu.exeris.kernel.spi.http.HttpStatus.NOT_FOUND);
            });
            case "clear" -> new Scenario(reg -> { withBase.accept(reg); reg.registerFilter(filter); },
                    DefaultApiSurfaceRegistry::clear, reg -> {
                assertThat(reg.filters()).isEmpty();
                assertThat(reg.routeCount()).isZero();
            });
            case "freeze" -> new Scenario(withBase, DefaultApiSurfaceRegistry::freeze, reg -> {
                assertThat(reg.isFrozen()).isTrue();
                assertThat(reg.isTearingDown()).isFalse();
            });
            case "beginTeardown" -> new Scenario(withBase, DefaultApiSurfaceRegistry::beginTeardown,
                    reg -> assertThat(reg.isTearingDown()).isTrue());
            default -> throw new IllegalArgumentException(mutator);
        };
    }

    static Stream<String> mutators() {
        return MUTATORS.stream();
    }

    /** Whether the registry can still be locked by another thread, which a leaked lock would prevent. */
    private static void assertLockIsFree(DefaultApiSurfaceRegistry registry) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(registry::beginTeardown).get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(registry.isTearingDown()).isTrue();
    }

    @ParameterizedTest(name = "{0}: a RuntimeException from the handler is swallowed and the change stands")
    @MethodSource("mutators")
    @DisplayName("a RuntimeException from the log handler")
    void runtimeExceptionIsSwallowed(String mutator) throws Exception {
        Scenario scenario = scenario(mutator);
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        scenario.arrange().accept(registry);

        try (ThrowingLog log = ThrowingLog.throwing(DefaultApiSurfaceRegistry.class,
                new IllegalStateException("log handler failed"))) {
            scenario.act().accept(registry);
            if (WRITE_A_RECORD.contains(mutator)) {
                assertThat(log.published()).as("the handler was reached, so the swallow is exercised").isEqualTo(1);
            }
        }

        scenario.verifyCommitted().accept(registry);
        assertLockIsFree(registry);
    }

    @ParameterizedTest(name = "{0}: an Error from the handler is swallowed and the change stands")
    @MethodSource("mutators")
    @DisplayName("an Error from the log handler")
    void errorIsSwallowed(String mutator) throws Exception {
        Scenario scenario = scenario(mutator);
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        scenario.arrange().accept(registry);

        try (ThrowingLog log = ThrowingLog.throwing(DefaultApiSurfaceRegistry.class,
                new AssertionError("log handler failed hard"))) {
            scenario.act().accept(registry);
            if (WRITE_A_RECORD.contains(mutator)) {
                assertThat(log.published()).isEqualTo(1);
            }
        }

        scenario.verifyCommitted().accept(registry);
        assertLockIsFree(registry);
    }

    @ParameterizedTest(name = "{0}: a VirtualMachineError from the handler propagates only where a record is written")
    @MethodSource("mutators")
    @DisplayName("a VirtualMachineError from the log handler")
    void virtualMachineErrorPropagates(String mutator) throws Exception {
        Scenario scenario = scenario(mutator);
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        scenario.arrange().accept(registry);
        InternalError failure = new InternalError("log handler: vm error");

        try (ThrowingLog log = ThrowingLog.throwing(DefaultApiSurfaceRegistry.class, failure)) {
            if (WRITE_A_RECORD.contains(mutator)) {
                assertThatThrownBy(() -> scenario.act().accept(registry)).isSameAs(failure);
                assertThat(log.published()).isEqualTo(1);
            } else {
                scenario.act().accept(registry);
                assertThat(log.published()).as("%s writes no record, so the handler is never reached", mutator).isZero();
            }
        }

        // The change was made before the record: an error from the record leaves the registry changed, and the
        // lock was released before the record was written.
        scenario.verifyCommitted().accept(registry);
        assertLockIsFree(registry);
    }

    @Test
    @DisplayName("exactly registerFilter and registerRoute write a record, once per successful call and none for a refusal")
    void whichMutatorsWriteARecord() {
        Set<String> writing = new LinkedHashSet<>();
        for (String mutator : MUTATORS) {
            Scenario scenario = scenario(mutator);
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            scenario.arrange().accept(registry);
            try (ThrowingLog log = new ThrowingLog(DefaultApiSurfaceRegistry.class, record -> { })) {
                scenario.act().accept(registry);
                if (log.published() > 0) {
                    writing.add(mutator);
                    assertThat(log.published()).as("%s writes one record", mutator).isEqualTo(1);
                }
            }
        }
        assertThat(writing).containsExactlyInAnyOrderElementsOf(WRITE_A_RECORD);

        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ProbeFilter filter = new ProbeFilter("f", AdmissionPhase.AUTHENTICATION, new ArrayList<>());
        registry.registerFilter(filter);
        try (ThrowingLog log = new ThrowingLog(DefaultApiSurfaceRegistry.class, record -> { })) {
            assertThatThrownBy(() -> registry.registerFilter(filter)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> registry.registerRoute(new ApiSurfaceRoute(
                    eu.exeris.kernel.spi.http.HttpMethod.GET, "/x", "X", List.of(), Fixtures.ok())))
                    .isInstanceOf(IllegalStateException.class);
            registry.freeze();
            assertThatThrownBy(() -> registry.registerRoute(Fixtures.open("/y"))).isInstanceOf(IllegalStateException.class);
            assertThat(log.published()).as("a refused registration writes no record").isZero();
        }
    }

    @Test
    @DisplayName("a refusal under a failing handler still throws its own exception and changes nothing")
    void refusalIsNotMaskedByTheHandler() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ProbeFilter filter = new ProbeFilter("f", AdmissionPhase.AUTHENTICATION, new ArrayList<>());
        ApiSurfaceRoute route = Fixtures.open("/r");
        registry.registerFilter(filter);
        registry.registerRoute(route);
        registry.freeze();

        try (ThrowingLog log = ThrowingLog.throwing(DefaultApiSurfaceRegistry.class, new InternalError("unreached"))) {
            assertThatThrownBy(() -> registry.registerFilter(new ProbeFilter("g", AdmissionPhase.AUTHENTICATION,
                    new ArrayList<>()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen");
            assertThatThrownBy(() -> registry.registerRoute(Fixtures.open("/s")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen");
            assertThat(log.published()).isZero();
        }

        assertSameInstances(registry.filters(), filter);
        assertThat(registry.routes()).containsExactly(route);
    }
}
