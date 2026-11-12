package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("When the registry's own answer fails")
class AnsweringFailureMatrixTest {

    private static final String PATH = "/api/thing";

    /** The failure that was being answered. */
    enum Original {
        EXCEPTION_IN_FILTER, EXCEPTION_IN_HANDLER,
        ERROR_IN_FILTER, ERROR_IN_HANDLER,
        VM_ERROR_IN_FILTER, VM_ERROR_IN_HANDLER,
        SILENT_FILTER, SILENT_HANDLER;

        boolean isException() { return this == EXCEPTION_IN_FILTER || this == EXCEPTION_IN_HANDLER; }

        boolean isError() { return this == ERROR_IN_FILTER || this == ERROR_IN_HANDLER; }

        boolean isVmError() { return this == VM_ERROR_IN_FILTER || this == VM_ERROR_IN_HANDLER; }

        boolean isSilent() { return this == SILENT_FILTER || this == SILENT_HANDLER; }

        boolean inFilter() {
            return this == EXCEPTION_IN_FILTER || this == ERROR_IN_FILTER || this == VM_ERROR_IN_FILTER
                    || this == SILENT_FILTER;
        }
    }

    /** The failure of the registry's own respond(500). */
    enum Answering { RUNTIME_EXCEPTION, ERROR, VM_ERROR }

    private DefaultApiSurfaceRegistry registry;
    private LogCapture log;
    private int handlerRuns;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    private static Throwable originalFailure(Original original) {
        if (original.isException()) {
            return new IllegalStateException("original exception");
        }
        if (original.isError()) {
            return new Probes.CustomError("original error");
        }
        if (original.isVmError()) {
            return new Probes.CustomVmError("original vm error");
        }
        return null;
    }

    private static Throwable answeringFailure(Answering answering) {
        return switch (answering) {
            case RUNTIME_EXCEPTION -> new IllegalArgumentException("answering failed");
            case ERROR -> new AnsweringError("answering failed");
            case VM_ERROR -> new AnsweringVmError("answering failed");
        };
    }

    /** Distinct classes so that a log record can be attributed to one failure by class name. */
    static final class AnsweringError extends Error {
        AnsweringError(String message) { super(message); }
    }

    static final class AnsweringVmError extends VirtualMachineError {
        AnsweringVmError(String message) { super(message); }
    }

    private void install(Original original, Throwable failure) {
        if (original.inFilter()) {
            registry.registerFilter(new CountingFilter("subject", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                return false;
            }));
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> {
                        handlerRuns++;
                        ex.respond(HttpStatus.OK);
                    }));
        } else {
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> {
                        handlerRuns++;
                        if (failure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (failure instanceof Error error) {
                            throw error;
                        }
                    }));
        }
    }

    private List<LogRecord> recordsNaming(Class<?> failureClass) {
        return log.at(Level.SEVERE).stream()
                .filter(r -> LogCapture.everything(r).contains(failureClass.getName()))
                .toList();
    }

    @Nested
    @DisplayName("the full matrix of original failure and answering failure")
    class Matrix {

        @ParameterizedTest(name = "{0} x {1}")
        @EnumSource(value = Original.class)
        @DisplayName("leaves handle() as the rule says, with the suppressed chain and the log records the rule names")
        void everyCell(Original original) {
            for (Answering answering : Answering.values()) {
                runCell(original, answering);
            }
        }
    }

    private void runCell(Original original, Answering answering) {
        setUpFreshRegistry();
        Throwable originalFailure = originalFailure(original);
        Throwable answerFailure = answeringFailure(answering);
        install(original, originalFailure);
        ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(answerFailure);
        String cell = original + " x " + answering;

        Throwable leaving = null;
        try {
            registry.handle(exchange);
        } catch (Throwable t) {
            leaving = t;
        }

        if (original.isVmError()) {
            // A VirtualMachineError is rethrown untouched, with no attempt to answer.
            assertThat(leaving).as(cell).isSameAs(originalFailure);
            assertThat(exchange.respondCount()).as(cell).isZero();
            assertThat(leaving.getSuppressed()).as(cell).isEmpty();
            assertThat(log.at(Level.SEVERE)).as(cell).isEmpty();
            return;
        }

        // The registry made exactly one attempt to answer 500.
        assertThat(exchange.statuses()).as(cell).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);

        if (answering == Answering.VM_ERROR) {
            // A VirtualMachineError from the attempt always propagates, with the original attached.
            assertThat(leaving).as(cell).isSameAs(answerFailure);
            if (originalFailure == null) {
                assertThat(leaving.getSuppressed()).as(cell).isEmpty();
            } else {
                assertThat(leaving.getSuppressed()).as(cell).containsExactly(originalFailure);
            }
            assertThat(recordsNaming(AnsweringVmError.class)).as(cell).isEmpty();
            return;
        }

        if (original.isError()) {
            // The original Error leaves, with the answering failure attached; nothing is logged for it.
            assertThat(leaving).as(cell).isSameAs(originalFailure);
            assertThat(leaving.getSuppressed()).as(cell).containsExactly(answerFailure);
            assertThat(recordsNaming(answerFailure.getClass())).as(cell).isEmpty();
            assertThat(log.at(Level.SEVERE)).as(cell).isEmpty();
            return;
        }

        // Original Exception, or nothing answered: handle() returns normally and the failure is logged once.
        assertThat(leaving).as(cell).isNull();
        if (originalFailure != null) {
            assertThat(originalFailure.getSuppressed()).as(cell).isEmpty();
        }
        List<LogRecord> answering500 = recordsNaming(answerFailure.getClass());
        assertThat(answering500).as(cell).hasSize(1);
        assertThat(answering500.get(0).getThrown()).as(cell).isNull();
        String text = LogCapture.everything(answering500.get(0));
        assertThat(text).as(cell).contains(PATH).doesNotContain("answering failed");
        assertThat(log.at(Level.SEVERE)).as(cell).hasSize(original.isException() ? 2 : 1);
    }

    private void setUpFreshRegistry() {
        log.close();
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
        handlerRuns = 0;
    }

    // ---- the registry's own answer to a denial ---------------------------------------------------------------

    /** Observed behaviour when the registry answers 401, 403 or a tenant conflict and that answer fails. */
    @Nested
    @DisplayName("when the registry's own denial cannot be written")
    class DenialAnswerFails {

        private final IllegalArgumentException failure = new IllegalArgumentException("write failed");

        private ScriptedExchange failing() {
            return ScriptedExchange.get(PATH).failOnRespondWith(failure);
        }

        private void routeWith(RouteRequirement requirement) {
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t", List.of(requirement), ex -> {
                handlerRuns++;
                ex.respond(HttpStatus.OK);
            }));
        }

        @Test
        @DisplayName("a 401 that fails to write: nothing answers again, the handler does not run, handle() returns, one ERROR is logged")
        void unauthorized() {
            routeWith(RouteRequirement.authenticated());
            ScriptedExchange exchange = failing();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
            assertThat(handlerRuns).isZero();
            assertThat(log.at(Level.SEVERE)).hasSize(1);
            LogRecord record = log.at(Level.SEVERE).get(0);
            assertThat(record.getThrown()).isNull();
            assertThat(LogCapture.everything(record)).contains(IllegalArgumentException.class.getName())
                    .doesNotContain("write failed");
        }

        @Test
        @DisplayName("a 403 for a missing scope that fails to write is handled in the same way")
        void forbidden() {
            registry.registerFilter(new CountingFilter("auth", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                AdmissionContext.current().authenticate(new Probes.Principal(Set.of("r")));
                return true;
            }));
            routeWith(RouteRequirement.requiringAllScopes(Set.of("w")));
            ScriptedExchange exchange = failing();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).isZero();
            assertThat(log.at(Level.SEVERE)).hasSize(1);
        }

        @Test
        @DisplayName("a denial that fails with an Error is logged once by class name and handle() returns, since no Error is being handled")
        void deniedWithError() {
            routeWith(RouteRequirement.authenticated());
            AnsweringError error = new AnsweringError("write failed");
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(error);

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(error.getSuppressed()).isEmpty();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
            assertThat(handlerRuns).isZero();
            assertThat(recordsNaming(AnsweringError.class)).hasSize(1);
            assertThat(log.at(Level.SEVERE)).hasSize(1);
            assertThat(LogCapture.everything(log.at(Level.SEVERE).get(0))).doesNotContain("write failed");
        }

        @Test
        @DisplayName("a tenant-conflict 403 from a filter that fails to write: nothing answers again and handle() returns after one ERROR")
        void tenantConflictFromFilter() {
            registry.registerFilter(new CountingFilter("conflict", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                throw new TenantConflictException();
            }));
            routeWith(RouteRequirement.permitAll());
            ScriptedExchange exchange = failing();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).isZero();
            assertThat(log.at(Level.SEVERE)).hasSize(1);
        }

        @Test
        @DisplayName("a tenant-conflict 403 from a handler that fails to write: handle() returns after one ERROR")
        void tenantConflictFromHandler() {
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> {
                        throw new TenantConflictException();
                    }));
            ScriptedExchange exchange = failing();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(log.at(Level.SEVERE)).hasSize(1);
            assertThat(LogCapture.everything(log.at(Level.SEVERE).get(0)))
                    .contains(IllegalArgumentException.class.getName()).doesNotContain("write failed");
        }

        @Test
        @DisplayName("a tenant-conflict 403 from a filter reached through execute() is logged once and execute returns false")
        void tenantConflictThroughExecute() {
            registry.registerFilter(new CountingFilter("conflict", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                throw new TenantConflictException();
            }));
            routeWith(RouteRequirement.permitAll());
            RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
            ScriptedExchange exchange = failing();

            boolean proceed = registry.execute(exchange, match);

            assertThat(proceed).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).isZero();
            assertThat(log.at(Level.SEVERE)).hasSize(1);
        }

        @Test
        @DisplayName("the ERROR record for a failed 401 or 403 names the route pattern")
        void denialRecordNamesThePattern() {
            routeWith(RouteRequirement.authenticated());

            registry.handle(failing());

            assertThat(LogCapture.everything(log.at(Level.SEVERE).get(0))).contains(PATH);
        }
    }
}
