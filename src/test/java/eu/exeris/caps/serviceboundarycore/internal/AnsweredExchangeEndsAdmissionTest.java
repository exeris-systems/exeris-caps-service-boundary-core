package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Once the exchange is answered, admission is over")
class AnsweredExchangeEndsAdmissionTest {

    private static final String PATTERN = "/orgs/{org}/things";
    private static final String HEADER_MARKER = "MARK-HEADER-9c1d";
    private static final String PARAM_MARKER = "MARK-PARAM-9c1d";
    private static final String QUERY_MARKER = "MARK-QUERY-9c1d";
    private static final List<String> MARKERS = List.of(HEADER_MARKER, PARAM_MARKER, QUERY_MARKER);
    private static final AdmissionPhase[] POSITIONS =
            {AdmissionPhase.PRE_ADMISSION, AdmissionPhase.AUTHENTICATION, AdmissionPhase.POST_ADMISSION};

    /** A filter of its own class, so that a log record naming it cannot be mistaken for a pass-through one. */
    static final class AnsweringFilter implements AdmissionFilter {
        private final AdmissionPhase phase;
        private final boolean verdict;
        private final Answer answer;
        final AtomicInteger calls = new AtomicInteger();

        interface Answer {
            void give(HttpExchange exchange) throws Exception;
        }

        AnsweringFilter(AdmissionPhase phase, boolean verdict, Answer answer) {
            this.phase = phase;
            this.verdict = verdict;
            this.answer = answer;
        }

        @Override public String name() { return "answering"; }

        @Override public AdmissionPhase phase() { return phase; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            calls.incrementAndGet();
            try {
                answer.give(exchange);
            } catch (RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (Exception checked) {
                throw new IllegalStateException(checked);
            }
            return verdict;
        }
    }

    private DefaultApiSurfaceRegistry registry;
    private LogCapture log;
    private AtomicInteger handlerRuns;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
        handlerRuns = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    private void route(HttpHandler handler) {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "Things#get",
                List.of(RouteRequirement.permitAll()), ex -> {
                    handlerRuns.incrementAndGet();
                    handler.handle(ex);
                }));
    }

    private static ScriptedExchange request() {
        return ScriptedExchange.of(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things?q=" + QUERY_MARKER,
                List.of(new HttpHeader("X-Marker", HEADER_MARKER)));
    }

    private List<LogRecord> endedAdmission() {
        return log.at(Level.WARNING);
    }

    /**
     * Three filters, one in each of the first, middle and last admission position. The one at {@code position}
     * answers; the others pass through. Returns the three, in admission order.
     */
    private List<AdmissionFilter> threeFilters(int position, boolean verdict, AnsweringFilter.Answer answer) {
        AdmissionFilter first = position == 0 ? new AnsweringFilter(POSITIONS[0], verdict, answer)
                : new CountingFilter("pass-0", POSITIONS[0], (ex, m) -> true);
        AdmissionFilter middle = position == 1 ? new AnsweringFilter(POSITIONS[1], verdict, answer)
                : new CountingFilter("pass-1", POSITIONS[1], (ex, m) -> true);
        AdmissionFilter last = position == 2 ? new AnsweringFilter(POSITIONS[2], verdict, answer)
                : new CountingFilter("pass-2", POSITIONS[2], (ex, m) -> true);
        List<AdmissionFilter> all = List.of(first, middle, last);
        all.forEach(registry::registerFilter);
        return all;
    }

    private static int calls(AdmissionFilter filter) {
        return filter instanceof AnsweringFilter a ? a.calls.get() : ((CountingFilter) filter).calls.get();
    }

    private void assertLaterFiltersSkipped(List<AdmissionFilter> filters, int position) {
        for (int i = 0; i < filters.size(); i++) {
            assertThat(calls(filters.get(i))).as("filter at position %d", i).isEqualTo(i <= position ? 1 : 0);
        }
    }

    private void assertNoMarkerAnywhere() {
        for (LogRecord record : log.records()) {
            String text = LogCapture.everything(record);
            for (String marker : MARKERS) {
                assertThat(text).doesNotContain(marker);
            }
        }
    }

    // ---- answers and returns true ---------------------------------------------------------------------------

    @Nested
    @DisplayName("a filter that answers and returns true")
    class AnswersAndProceeds {

        @ParameterizedTest(name = "at position {0} of three: through handle()")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("stops the later filters and the handler, leaves exactly one answer and logs one WARNING")
        void throughHandle(int position) {
            List<AdmissionFilter> filters = threeFilters(position, true, ex -> ex.respond(HttpStatus.ACCEPTED));
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request();

            registry.handle(exchange);

            assertLaterFiltersSkipped(filters, position);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
            assertThat(endedAdmission()).hasSize(1);
            String text = LogCapture.everything(endedAdmission().get(0));
            assertThat(text).contains(PATTERN).contains(AnsweringFilter.class.getName())
                    .doesNotContain(CountingFilter.class.getName());
            assertNoMarkerAnywhere();
        }

        @ParameterizedTest(name = "at position {0} of three: through execute()")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("makes RequestFilterChain.execute return false, the documented value for a short-circuit")
        void throughExecute(int position) {
            List<AdmissionFilter> filters = threeFilters(position, true, ex -> ex.respond(HttpStatus.ACCEPTED));
            route(ex -> ex.respond(HttpStatus.OK));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();
            ScriptedExchange exchange = request();

            boolean proceed = registry.execute(exchange, match);

            assertThat(proceed).isFalse();
            assertLaterFiltersSkipped(filters, position);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
            assertThat(endedAdmission()).hasSize(1);
            assertThat(LogCapture.everything(endedAdmission().get(0)))
                    .contains(PATTERN).contains(AnsweringFilter.class.getName());
            assertNoMarkerAnywhere();
        }

        @ParameterizedTest(name = "overload {0}")
        @CsvSource({"status", "statusAndBody", "response", "typed"})
        @DisplayName("counts every respond overload as an answer")
        void everyOverloadCounts(String overload) {
            AnsweringFilter.Answer answer = switch (overload) {
                case "status" -> ex -> ex.respond(HttpStatus.ACCEPTED);
                case "statusAndBody" -> ex -> ex.respond(HttpStatus.ACCEPTED, "body");
                case "response" -> ex -> ex.respond(new HttpResponse(HttpStatus.ACCEPTED, HttpVersion.HTTP_1_1,
                        List.of(), null));
                default -> ex -> ex.respond(HttpStatus.ACCEPTED, (Object) null);
            };
            List<AdmissionFilter> filters = threeFilters(1, true, answer);
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request();

            registry.handle(exchange);

            assertLaterFiltersSkipped(filters, 1);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.respondCount()).isEqualTo(1);
            assertThat(endedAdmission()).hasSize(1);
        }

        @ParameterizedTest(name = "at position {0} of three")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("that answered on another thread and joined it before returning ends admission just the same")
        void answeredOnAnotherThread(int position) {
            List<AdmissionFilter> filters = threeFilters(position, true, ex -> {
                Thread other = Thread.ofVirtual().unstarted(() -> ex.respond(HttpStatus.ACCEPTED));
                other.start();
                other.join();
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request();

            registry.handle(exchange);

            assertLaterFiltersSkipped(filters, position);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
            assertThat(endedAdmission()).hasSize(1);
            assertNoMarkerAnywhere();
        }

        @Test
        @DisplayName("is logged once per request, not once per filter that follows it")
        void oneWarningPerRequest() {
            threeFilters(0, true, ex -> ex.respond(HttpStatus.ACCEPTED));
            route(ex -> ex.respond(HttpStatus.OK));

            registry.handle(request());
            registry.handle(request());

            assertThat(endedAdmission()).hasSize(2);
        }
    }

    // ---- answers and returns false ---------------------------------------------------------------------------

    @Nested
    @DisplayName("a filter that answers and returns false")
    class AnswersAndHalts {

        @ParameterizedTest(name = "at position {0} of three: through handle()")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("stops the later filters and the handler and logs nothing at WARNING or above, only a DEBUG record")
        void throughHandle(int position) {
            List<AdmissionFilter> filters = threeFilters(position, false, ex -> ex.respond(HttpStatus.TOO_MANY_REQUESTS));
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request();

            registry.handle(exchange);

            assertLaterFiltersSkipped(filters, position);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
            assertThat(log.at(Level.FINE).stream()
                    .filter(r -> LogCapture.everything(r).contains("halted admission pipeline"))).hasSize(1);
        }

        @ParameterizedTest(name = "at position {0} of three: through execute()")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("makes execute return false without a WARNING")
        void throughExecute(int position) {
            List<AdmissionFilter> filters = threeFilters(position, false, ex -> ex.respond(HttpStatus.TOO_MANY_REQUESTS));
            route(ex -> ex.respond(HttpStatus.OK));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();
            ScriptedExchange exchange = request();

            assertThat(registry.execute(exchange, match)).isFalse();

            assertLaterFiltersSkipped(filters, position);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
        }

        @Test
        @DisplayName("that answered on another thread and joined it logs nothing at WARNING or above")
        void answeredOnAnotherThread() {
            List<AdmissionFilter> filters = threeFilters(1, false, ex -> {
                Thread other = Thread.ofVirtual().unstarted(() -> ex.respond(HttpStatus.TOO_MANY_REQUESTS));
                other.start();
                other.join();
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request();

            registry.handle(exchange);

            assertLaterFiltersSkipped(filters, 1);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
        }
    }

    // ---- a second respond is the kernel's refusal ------------------------------------------------------------

    @Nested
    @DisplayName("a second respond, refused by the kernel's exchange")
    class SecondRespondRefused {

        @Test
        @DisplayName("by a handler: one recorded answer, handle() returns normally, one ERROR record, and no 500 on an answered exchange")
        void handlerRespondsTwice() {
            HttpHandler handler = ex -> {
                ex.respond(HttpStatus.OK);
                ex.respond(HttpStatus.OK);
            };
            route(handler);
            ScriptedExchange exchange = request().refuseSecondAnswer();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertThat(exchange.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.OK));
            assertThat(log.atOrAbove(Level.WARNING)).hasSize(1);
            LogRecord record = log.at(Level.SEVERE).get(0);
            assertThat(record.getThrown()).isNull();
            String text = LogCapture.everything(record);
            assertThat(text).contains(IllegalStateException.class.getName()).contains(PATTERN)
                    .doesNotContain("already been answered");
            assertNoMarkerAnywhere();
        }

        @Test
        @DisplayName("by a filter: one recorded answer, the later filters and the handler do not run, handle() returns normally")
        void filterRespondsTwice() {
            List<AdmissionFilter> filters = threeFilters(1, true, ex -> {
                ex.respond(HttpStatus.FORBIDDEN);
                ex.respond(HttpStatus.FORBIDDEN);
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request().refuseSecondAnswer();

            assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

            assertLaterFiltersSkipped(filters, 1);
            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(log.atOrAbove(Level.WARNING)).hasSize(1);
            assertThat(LogCapture.everything(log.at(Level.SEVERE).get(0)))
                    .contains(IllegalStateException.class.getName()).contains(PATTERN);
            assertNoMarkerAnywhere();
        }

        @Test
        @DisplayName("by a filter, through execute(): the refusal is logged and execute returns false")
        void filterRespondsTwiceThroughExecute() {
            threeFilters(0, true, ex -> {
                ex.respond(HttpStatus.FORBIDDEN);
                ex.respond(HttpStatus.FORBIDDEN);
            });
            route(ex -> ex.respond(HttpStatus.OK));
            RouteMatch match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();
            ScriptedExchange exchange = request().refuseSecondAnswer();

            assertThat(registry.execute(exchange, match)).isFalse();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(log.at(Level.SEVERE)).hasSize(1);
        }
    }

    // ---- an answer that failed before anything was written -----------------------------------------------------

    /**
     * The README states that no matched exchange is left unanswered, and that the registry answers 500 when a
     * filter or handler throws an Exception. An exchange whose first respond failed before writing anything
     * has not been answered, so the client is owed the 500.
     */
    @Nested
    @DisplayName("a first respond that fails before writing anything")
    class FirstRespondFailsBeforeWriting {

        @Test
        @DisplayName("in a handler that lets the failure escape: the exchange still ends up answered")
        void handlerLetsItEscape() {
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request()
                    .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("cannot encode"));

            registry.handle(exchange);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("in a handler that swallows the failure and returns: the exchange still ends up answered")
        void handlerSwallowsIt() {
            route(ex -> {
                try {
                    ex.respond(HttpStatus.OK);
                } catch (IllegalArgumentException swallowed) {
                    // the handler believes it has done what it can
                }
            });
            ScriptedExchange exchange = request()
                    .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("cannot encode"));

            registry.handle(exchange);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("in a filter that lets the failure escape: the exchange still ends up answered and the handler does not run")
        void filterLetsItEscape() {
            registry.registerFilter(new CountingFilter("denies", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
                ex.respond(HttpStatus.FORBIDDEN);
                return false;
            }));
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request()
                    .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("cannot encode"));

            registry.handle(exchange);

            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("is still logged at ERROR for the Exception that escaped")
        void failureIsLogged() {
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = request()
                    .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("cannot encode"));

            registry.handle(exchange);

            assertThat(log.at(Level.SEVERE)).hasSize(1);
            assertThat(LogCapture.everything(log.at(Level.SEVERE).get(0)))
                    .contains(IllegalArgumentException.class.getName()).contains(PATTERN);
        }
    }
}
