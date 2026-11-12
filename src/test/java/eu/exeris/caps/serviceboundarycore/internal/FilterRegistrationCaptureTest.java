package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.SpyFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.SpyFilter.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A filter's name and phase are read once, at registration, and a refused registration changes nothing")
class FilterRegistrationCaptureTest {

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private DefaultApiSurfaceRegistry registry;
    private SpyFilter authenticating;
    private SpyFilter pre;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/p"));
        authenticating = new SpyFilter("a-auth", AdmissionPhase.AUTHENTICATION, trace);
        pre = new SpyFilter("b-pre", AdmissionPhase.PRE_ADMISSION, trace);
        registry.registerFilter(authenticating);
        registry.registerFilter(pre);
    }

    /** Runs one request and returns the filters that ran, in order. */
    private List<String> serve() {
        trace.clear();
        registry.handle(ScriptedExchange.get("/p"));
        return List.copyOf(trace);
    }

    private void assertRefusedAndUnchanged(SpyFilter refused, Class<? extends Throwable> expected) {
        List<AdmissionFilter> before = registry.filters();
        List<String> ranBefore = serve();

        assertThatThrownBy(() -> registry.registerFilter(refused)).isInstanceOf(expected);

        assertThat(registry.filters()).isEqualTo(before).containsExactly(pre, authenticating);
        assertThat(serve()).isEqualTo(ranBefore).containsExactly("b-pre", "a-auth");
        assertThat(refused.runs.get()).isZero();
    }

    private static final class Scripted extends RuntimeException {
        Scripted(String message) {
            super(message);
        }
    }

    // ---- refused registrations -----------------------------------------------------------------------------

    @Test
    @DisplayName("a filter whose name() throws is refused and the registry is unchanged")
    void nameThrows() {
        SpyFilter filter = new SpyFilter("x", AdmissionPhase.RATE_LIMITING, trace).throwsOnName(new Scripted("name"));

        assertRefusedAndUnchanged(filter, Scripted.class);
        assertThat(filter.nameCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a filter whose phase() throws is refused, and the name it reported is not reserved")
    void phaseThrows() {
        SpyFilter filter = new SpyFilter("reported", AdmissionPhase.RATE_LIMITING, trace)
                .throwsOnPhase(new Scripted("phase"));

        assertRefusedAndUnchanged(filter, Scripted.class);
        assertThat(filter.nameCalls.get()).isEqualTo(1);
        assertThat(filter.phaseCalls.get()).isEqualTo(1);

        registry.registerFilter(new SpyFilter("reported", AdmissionPhase.RATE_LIMITING, trace));
        assertThat(registry.filters()).hasSize(3);
    }

    @Test
    @DisplayName("a filter whose name() returns null, empty or blank is refused with IllegalArgumentException")
    void nameNullOrBlank() {
        for (String bad : new String[] {null, "", " ", "   ", "\t", "\n"}) {
            SpyFilter filter = new SpyFilter("x", AdmissionPhase.RATE_LIMITING, trace).reportsName(bad);

            assertRefusedAndUnchanged(filter, IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a filter whose phase() returns null is refused, and the name it reported is not reserved")
    void phaseNull() {
        SpyFilter filter = new SpyFilter("reported", null, trace);

        assertRefusedAndUnchanged(filter, IllegalArgumentException.class);

        registry.registerFilter(new SpyFilter("reported", AdmissionPhase.AUTHORIZATION, trace));
        assertThat(registry.filters()).hasSize(3);
    }

    @Test
    @DisplayName("the same reference registered twice is refused and the first registration stays as it was")
    void sameReferenceTwice() {
        List<AdmissionFilter> before = registry.filters();

        assertThatThrownBy(() -> registry.registerFilter(pre)).isInstanceOf(IllegalStateException.class);
        pre.reportsName("renamed");
        assertThatThrownBy(() -> registry.registerFilter(pre)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.filters()).isEqualTo(before);
        assertThat(serve()).containsExactly("b-pre", "a-auth");
    }

    @Test
    @DisplayName("a duplicate name is refused against the name captured at registration, not the live one")
    void duplicateNameUsesCapturedName() {
        pre.reportsName("moved-on");

        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("b-pre", AdmissionPhase.PRE_ADMISSION, trace)))
                .isInstanceOf(IllegalStateException.class);
        registry.registerFilter(new SpyFilter("moved-on", AdmissionPhase.PRE_ADMISSION, trace));

        assertThat(registry.filters()).hasSize(3);
    }

    // ---- exactly one read ----------------------------------------------------------------------------------

    @Test
    @DisplayName("name() and phase() are each called once per successful registration and never again")
    void readOnceAndNeverAgain() {
        List<AdmissionFilter> others = new ArrayList<>();
        SpyFilter spy = new SpyFilter("spy", AdmissionPhase.AUTHENTICATION, trace);
        registry.registerFilter(spy);
        assertThat(spy.nameCalls.get()).isEqualTo(1);
        assertThat(spy.phaseCalls.get()).isEqualTo(1);

        for (int i = 0; i < 6; i++) {
            SpyFilter other = new SpyFilter("other-" + i, AdmissionPhase.values()[i % 4], trace);
            others.add(other);
            registry.registerFilter(other);
        }
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("spy", AdmissionPhase.AUTHENTICATION, trace)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.unregisterFilter(others.get(1))).isTrue();
        assertThat(registry.unregisterFilter(others.get(4))).isTrue();
        assertThat(registry.unregisterFilter(new SpyFilter("never-registered", AdmissionPhase.RATE_LIMITING, trace)))
                .isFalse();
        registry.filters();
        serve();
        serve();
        assertThat(spy.runs.get()).isEqualTo(2);

        registry.freeze();
        registry.filters();
        serve();
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("late", AdmissionPhase.AUTHENTICATION, trace)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.unregisterFilter(others.get(0))).isInstanceOf(IllegalStateException.class);
        registry.beginTeardown();
        registry.filters();
        serve();
        assertThat(registry.unregisterFilter(others.get(2))).isTrue();
        registry.clear();
        registry.filters();

        assertThat(spy.nameCalls.get()).as("name() calls").isEqualTo(1);
        assertThat(spy.phaseCalls.get()).as("phase() calls").isEqualTo(1);
        for (AdmissionFilter other : others) {
            SpyFilter s = (SpyFilter) other;
            assertThat(s.nameCalls.get()).as("name() calls of %s", s).isEqualTo(1);
            assertThat(s.phaseCalls.get()).as("phase() calls of %s", s).isEqualTo(1);
        }
        assertThat(authenticating.calls()).isEqualTo(2);
        assertThat(pre.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("a filter registered again after it was removed is read once more, once")
    void reRegistrationReadsOnceMore() {
        assertThat(registry.unregisterFilter(pre)).isTrue();
        registry.registerFilter(pre);

        assertThat(pre.nameCalls.get()).isEqualTo(2);
        assertThat(pre.phaseCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a filter that reports another name and phase afterwards keeps its place whatever the registry does next")
    void driftingFilterKeepsItsPlace() {
        authenticating.reportsName("zz").reportsPhase(AdmissionPhase.PRE_ADMISSION);
        pre.reportsName("aa").reportsPhase(AdmissionPhase.AUTHORIZATION);

        registry.registerFilter(new SpyFilter("c-rate", AdmissionPhase.RATE_LIMITING, trace));
        registry.registerFilter(new SpyFilter("a-0", AdmissionPhase.PRE_ADMISSION, trace));
        assertThat(registry.unregisterFilter(new SpyFilter("nobody", AdmissionPhase.PRE_ADMISSION, trace))).isFalse();

        assertThat(serve()).containsExactly("a-0", "b-pre", "c-rate", "a-auth");
    }

    // ---- the log records -----------------------------------------------------------------------------------

    @Test
    @DisplayName("the registration record names the name and phase captured at registration; no record shows a live name")
    void logRecordsNameTheRegisteredNameOnly() {
        try (LogCapture capture = new LogCapture(DefaultApiSurfaceRegistry.class)) {
            SpyFilter spy = new SpyFilter("registered-name", AdmissionPhase.RATE_LIMITING, trace);
            registry.registerFilter(spy);

            List<LogRecord> registration = capture.records().stream()
                    .filter(r -> LogCapture.everything(r).contains("registered-name")).toList();
            assertThat(registration).hasSize(1);
            assertThat(registration.get(0).getLevel()).isEqualTo(java.util.logging.Level.FINE);
            assertThat(LogCapture.everything(registration.get(0))).contains("RATE_LIMITING");

            spy.reportsName("LIVE-NAME").reportsPhase(AdmissionPhase.AUTHORIZATION);
            for (Mode mode : new Mode[] {Mode.ANSWER_AND_HALT, Mode.TENANT_CONFLICT, Mode.HALT_WITHOUT_ANSWER, Mode.THROW}) {
                spy.mode(mode);
                serve();
            }
            spy.mode(Mode.PASS);
            serve();
            registry.unregisterFilter(spy);
            registry.registerFilter(new SpyFilter("another", AdmissionPhase.RATE_LIMITING, trace));
            registry.filters();
            registry.clear();

            assertThat(capture.records()).isNotEmpty();
            for (LogRecord record : capture.records()) {
                assertThat(LogCapture.everything(record)).as("record %s", record.getMessage())
                        .doesNotContain("LIVE-NAME");
            }
            long naming = capture.records().stream()
                    .filter(r -> LogCapture.everything(r).contains(SpyFilter.class.getName())).count();
            assertThat(naming).as("records that name the filter's class").isEqualTo(4);
        }
    }

    @Test
    @DisplayName("the DEBUG records of a halted pipeline and of a tenant conflict name the filter's class")
    void debugRecordsNameTheClass() {
        try (LogCapture capture = new LogCapture(DefaultApiSurfaceRegistry.class)) {
            pre.mode(Mode.ANSWER_AND_HALT);
            serve();
            pre.mode(Mode.TENANT_CONFLICT);
            serve();

            List<LogRecord> debug = capture.at(java.util.logging.Level.FINE);
            assertThat(debug).hasSize(2);
            for (LogRecord record : debug) {
                assertThat(LogCapture.everything(record)).contains(SpyFilter.class.getName()).doesNotContain("b-pre");
            }
        }
    }
}
