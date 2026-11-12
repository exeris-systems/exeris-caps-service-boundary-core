package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.ClaimingExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A first respond that throws: before the kernel claims the response, and after")
class FirstRespondFailureTest {

    private static final String PATH = "/api/thing";

    /** Where the failing respond is made and what its caller does with the failure. */
    enum Caller {
        HANDLER_LETS_IT_ESCAPE(false, true, false),
        HANDLER_SWALLOWS_IT(false, false, false),
        FILTER_LETS_IT_ESCAPE(true, true, false),
        FILTER_SWALLOWS_AND_RETURNS_TRUE(true, false, true),
        FILTER_SWALLOWS_AND_RETURNS_FALSE(true, false, false);

        final boolean inFilter;
        final boolean escapes;
        final boolean verdict;

        Caller(boolean inFilter, boolean escapes, boolean verdict) {
            this.inFilter = inFilter;
            this.escapes = escapes;
            this.verdict = verdict;
        }
    }

    /** The failure of the first respond; its own class, so a log record can be told from the answering failure. */
    static final class EncoderFailure extends RuntimeException {
        EncoderFailure() {
            super("encoder failure");
        }
    }

    private DefaultApiSurfaceRegistry registry;
    private LogCapture log;
    private final AtomicInteger handlerRuns = new AtomicInteger();

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    private void install(Caller caller) {
        if (caller.inFilter) {
            registry.registerFilter(new CountingFilter("subject", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                try {
                    ex.respond(HttpStatus.OK);
                } catch (RuntimeException failed) {
                    if (caller.escapes) {
                        throw failed;
                    }
                }
                return caller.verdict;
            }));
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> {
                        handlerRuns.incrementAndGet();
                        ex.respond(HttpStatus.OK);
                    }));
        } else {
            registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                    List.of(RouteRequirement.permitAll()), ex -> {
                        handlerRuns.incrementAndGet();
                        try {
                            ex.respond(HttpStatus.OK);
                        } catch (RuntimeException failed) {
                            if (caller.escapes) {
                                throw failed;
                            }
                        }
                    }));
        }
    }

    private List<LogRecord> errorsNaming(Class<?> failure) {
        return log.at(Level.SEVERE).stream()
                .filter(r -> LogCapture.everything(r).contains(failure.getName())).toList();
    }

    @Nested
    @DisplayName("before the kernel claims the response: nothing is recorded and a later respond succeeds")
    class BeforeTheClaim {

        private ScriptedExchange exchange() {
            return ScriptedExchange.get(PATH).failFirstRespondBeforeRecordingWith(new EncoderFailure());
        }

        @ParameterizedTest(name = "{0} through handle")
        @EnumSource(Caller.class)
        @DisplayName("the registry's 500 is recorded exactly once, through handle()")
        void throughHandle(Caller caller) {
            install(caller);
            ScriptedExchange exchange = exchange();

            registry.handle(exchange);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(errorsNaming(IllegalStateException.class)).isEmpty();
            if (caller.inFilter) {
                assertThat(handlerRuns).hasValue(0);
            }
        }

        @ParameterizedTest(name = "{0} through execute")
        @EnumSource(value = Caller.class, names = {"FILTER_LETS_IT_ESCAPE", "FILTER_SWALLOWS_AND_RETURNS_TRUE",
                "FILTER_SWALLOWS_AND_RETURNS_FALSE"})
        @DisplayName("the registry's 500 is recorded exactly once, through execute(), which returns false")
        void throughExecute(Caller caller) {
            install(caller);
            RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
            ScriptedExchange exchange = exchange();

            boolean proceed = registry.execute(exchange, match);

            assertThat(proceed).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handlerRuns).hasValue(0);
        }
    }

    @Nested
    @DisplayName("after the kernel claims the response: every later respond is refused with IllegalStateException")
    class AfterTheClaim {

        private ClaimingExchange exchange() {
            return new ClaimingExchange(HttpMethod.GET, PATH, new EncoderFailure());
        }

        @ParameterizedTest(name = "{0} through handle")
        @EnumSource(Caller.class)
        @DisplayName("the 500 attempt is refused: one ERROR by class name, handle() returns normally, nothing propagates")
        void throughHandle(Caller caller) {
            install(caller);
            ClaimingExchange exchange = exchange();

            registry.handle(exchange);

            assertThat(exchange.attempts()).containsExactly(HttpStatus.OK, HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(errorsNaming(IllegalStateException.class)).hasSize(1);
            assertThat(errorsNaming(IllegalStateException.class).get(0).getThrown()).isNull();
            if (caller.inFilter) {
                assertThat(handlerRuns).hasValue(0);
            }
        }

        @ParameterizedTest(name = "{0} through execute")
        @EnumSource(value = Caller.class, names = {"FILTER_LETS_IT_ESCAPE", "FILTER_SWALLOWS_AND_RETURNS_TRUE",
                "FILTER_SWALLOWS_AND_RETURNS_FALSE"})
        @DisplayName("the 500 attempt is refused: one ERROR by class name, execute() returns false, nothing propagates")
        void throughExecute(Caller caller) {
            install(caller);
            RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
            ClaimingExchange exchange = exchange();

            boolean proceed = registry.execute(exchange, match);

            assertThat(proceed).isFalse();
            assertThat(exchange.attempts()).containsExactly(HttpStatus.OK, HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(errorsNaming(IllegalStateException.class)).hasSize(1);
            assertThat(handlerRuns).hasValue(0);
        }
    }
}
