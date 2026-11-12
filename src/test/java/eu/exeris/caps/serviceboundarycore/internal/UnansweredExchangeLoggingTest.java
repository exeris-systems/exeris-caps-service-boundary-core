package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Logging of unanswered exchanges")
class UnansweredExchangeLoggingTest {

    private static final String PATTERN = "/orgs/{org}/things";
    private static final String HEADER_MARKER = "MARK-HEADER-7f3a";
    private static final String PARAM_MARKER = "MARK-PARAM-7f3a";
    private static final String QUERY_MARKER = "MARK-QUERY-7f3a";
    private static final String MESSAGE_MARKER = "MARK-MESSAGE-7f3a";
    private static final List<String> MARKERS = List.of(HEADER_MARKER, PARAM_MARKER, QUERY_MARKER, MESSAGE_MARKER);

    private DefaultApiSurfaceRegistry registry;
    private LogCapture log;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    private void route(HttpHandler handler) {
        route(List.of(RouteRequirement.permitAll()), handler);
    }

    private void route(List<RouteRequirement> requirements, HttpHandler handler) {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "Things#get", requirements, handler));
    }

    private static ScriptedExchange request() {
        return ScriptedExchange.of(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things?q=" + QUERY_MARKER,
                List.of(new HttpHeader("X-Marker", HEADER_MARKER)));
    }

    private void handleAllowingErrors(ScriptedExchange exchange) {
        try {
            registry.handle(exchange);
        } catch (Error expected) {
            // the rethrown Error is the subject of other tests
        }
    }

    private LogRecord onlyWarning() {
        List<LogRecord> warnings = log.at(Level.WARNING);
        assertThat(warnings).hasSize(1);
        return warnings.get(0);
    }

    private void assertNoMarkerAnywhere(List<LogRecord> records) {
        for (LogRecord record : records) {
            String text = LogCapture.everything(record);
            for (String marker : MARKERS) {
                assertThat(text).doesNotContain(marker);
            }
        }
    }

    // ---- exactly one WARNING per unanswered case -----------------------------------------------------------

    @Test
    @DisplayName("a filter that returns false without answering logs one WARNING naming the pattern and the filter class")
    void unansweredFilter() {
        Probes.CountingFilter filter = Probes.filter("halts", (ex, m) -> false);
        registry.registerFilter(filter);
        route(ex -> ex.respond(HttpStatus.OK));

        registry.handle(request());

        String text = LogCapture.everything(onlyWarning());
        assertThat(text).contains(PATTERN).contains(Probes.CountingFilter.class.getName());
        assertThat(log.atOrAbove(Level.SEVERE)).isEmpty();
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("a handler that returns without answering logs one WARNING naming the pattern and the handler class")
    void unansweredHandler() {
        HttpHandler handler = new HttpHandler() {
            @Override public void handle(eu.exeris.kernel.spi.http.HttpExchange exchange) { }
        };
        route(handler);

        registry.handle(request());

        String text = LogCapture.everything(onlyWarning());
        assertThat(text).contains(PATTERN);
        // the registry sees the route's handler, which is the object that was registered
        assertThat(text).contains(handler.getClass().getName());
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("an Error from a filter before any answer logs one WARNING naming the pattern and the filter class, and not the Error's message")
    void errorFromFilter() {
        registry.registerFilter(Probes.filter("errs", (ex, m) -> {
            throw new Probes.CustomError(MESSAGE_MARKER);
        }));
        route(ex -> ex.respond(HttpStatus.OK));

        handleAllowingErrors(request());

        String text = LogCapture.everything(onlyWarning());
        assertThat(text).contains(PATTERN).contains(Probes.CountingFilter.class.getName());
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("an Error from a handler before any answer logs one WARNING naming the pattern and the handler class, and not the Error's message")
    void errorFromHandler() {
        HttpHandler handler = new HttpHandler() {
            @Override public void handle(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                throw new Probes.CustomError(MESSAGE_MARKER);
            }
        };
        route(handler);

        handleAllowingErrors(request());

        String text = LogCapture.everything(onlyWarning());
        assertThat(text).contains(PATTERN).contains(handler.getClass().getName());
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("an Error whose message is a marker does not reach any record even when answering fails")
    void errorMessageNotLoggedWhenAnsweringFails() {
        route(ex -> {
            throw new Probes.CustomError(MESSAGE_MARKER);
        });
        ScriptedExchange exchange = request().failOnRespondWith(new IllegalStateException(HEADER_MARKER));

        handleAllowingErrors(exchange);

        assertThat(log.at(Level.WARNING)).hasSize(1);
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("no WARNING for an Error after the exchange was answered")
    void noWarningForErrorAfterAnswer() {
        route(ex -> {
            ex.respond(HttpStatus.OK);
            throw new Probes.CustomError("late");
        });

        handleAllowingErrors(request());

        assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
    }

    @Test
    @DisplayName("no WARNING for a VirtualMachineError, which is rethrown without an attempt to answer")
    void noWarningForVmError() {
        route(ex -> {
            throw new Probes.CustomVmError("vm");
        });

        handleAllowingErrors(request());

        assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
    }

    @Test
    @DisplayName("an unanswered false through RequestFilterChain.execute logs one WARNING with the pattern and filter class")
    void unansweredFalseThroughExecute() {
        registry.registerFilter(Probes.filter("halts", (ex, m) -> false));
        route(ex -> ex.respond(HttpStatus.OK));
        var match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();

        registry.execute(request(), match);

        String text = LogCapture.everything(onlyWarning());
        assertThat(text).contains(PATTERN).contains(Probes.CountingFilter.class.getName());
        assertNoMarkerAnywhere(log.records());
    }

    @Test
    @DisplayName("each unanswered request logs its own single WARNING")
    void oneWarningPerRequest() {
        route(ex -> { });

        registry.handle(request());
        registry.handle(request());
        registry.handle(request());

        assertThat(log.at(Level.WARNING)).hasSize(3);
    }

    // ---- no header or body value in any record -------------------------------------------------------------

    @Test
    @DisplayName("an Exception from a handler does not put a header, path parameter, query or message marker into any record's text")
    void exceptionRecordCarriesNoRequestValue() {
        route(ex -> {
            throw new IllegalStateException(MESSAGE_MARKER);
        });

        registry.handle(request());

        assertThat(log.records()).isNotEmpty();
        for (LogRecord record : log.records()) {
            String text = LogCapture.messageText(record);
            for (String marker : List.of(HEADER_MARKER, PARAM_MARKER, QUERY_MARKER, MESSAGE_MARKER)) {
                assertThat(text).doesNotContain(marker);
            }
        }
    }

    @Test
    @DisplayName("an Exception from a filter does not put a header, path parameter or query marker into any record, throwable included")
    void filterExceptionRecordCarriesNoRequestValue() {
        registry.registerFilter(Probes.filter("boom", (ex, m) -> {
            throw new IllegalStateException("boom");
        }));
        route(ex -> ex.respond(HttpStatus.OK));

        registry.handle(request());

        for (LogRecord record : log.records()) {
            String text = LogCapture.everything(record);
            for (String marker : List.of(HEADER_MARKER, PARAM_MARKER, QUERY_MARKER)) {
                assertThat(text).doesNotContain(marker);
            }
        }
    }

    // ---- silence on the ordinary paths -----------------------------------------------------------------------

    @Test
    @DisplayName("the success path logs nothing at WARNING or above")
    void successPathIsQuiet() {
        registry.registerFilter(Probes.filter("passes", (ex, m) -> true));
        route(ex -> ex.respond(HttpStatus.OK));

        registry.handle(request());

        assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
    }

    @Test
    @DisplayName("ordinary denials and non-matches log nothing at WARNING or above: 401, 403, filter denial, tenant conflict, 404, 405, 503")
    void ordinaryDenialsAreQuiet() {
        // 401
        route(List.of(RouteRequirement.authenticated()), ex -> ex.respond(HttpStatus.OK));
        ScriptedExchange unauthorized = request();
        registry.handle(unauthorized);
        assertThat(unauthorized.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);

        // 404 and 405
        ScriptedExchange notFound = ScriptedExchange.get("/nowhere");
        registry.handle(notFound);
        assertThat(notFound.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        ScriptedExchange wrongMethod = ScriptedExchange.of(HttpMethod.POST, "/orgs/x/things", List.of());
        registry.handle(wrongMethod);
        assertThat(wrongMethod.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);

        // 403 from a scope requirement
        DefaultApiSurfaceRegistry scoped = new DefaultApiSurfaceRegistry();
        scoped.registerFilter(Probes.filter("auth", (ex, m) -> {
            AdmissionContext.current().authenticate(new Probes.Principal(Set.of("r")));
            return true;
        }));
        scoped.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/s", "S#g",
                List.of(RouteRequirement.requiringAllScopes(Set.of("w"))), ex -> ex.respond(HttpStatus.OK)));
        ScriptedExchange forbidden = ScriptedExchange.get("/s");
        scoped.handle(forbidden);
        assertThat(forbidden.statuses()).containsExactly(HttpStatus.FORBIDDEN);

        // a filter denial that answered
        DefaultApiSurfaceRegistry denying = new DefaultApiSurfaceRegistry();
        denying.registerFilter(Probes.filter("limit", (ex, m) -> {
            ex.respond(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }));
        denying.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/d", "D#g",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        ScriptedExchange limited = ScriptedExchange.get("/d");
        denying.handle(limited);
        assertThat(limited.statuses()).containsExactly(HttpStatus.TOO_MANY_REQUESTS);

        // tenant conflict
        DefaultApiSurfaceRegistry conflicting = new DefaultApiSurfaceRegistry();
        conflicting.registerFilter(new Probes.CountingFilter("conflict", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            throw new TenantConflictException();
        }));
        conflicting.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/c", "C#g",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        ScriptedExchange conflict = ScriptedExchange.get("/c");
        conflicting.handle(conflict);
        assertThat(conflict.statuses()).containsExactly(HttpStatus.FORBIDDEN);

        // 503
        registry.beginTeardown();
        ScriptedExchange teardown = request();
        registry.handle(teardown);
        assertThat(teardown.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
    }
}
