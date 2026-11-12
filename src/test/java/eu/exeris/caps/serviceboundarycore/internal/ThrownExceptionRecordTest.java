package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
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
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The ERROR record for a thrown Exception")
class ThrownExceptionRecordTest {

    private static final String PATTERN = "/orgs/{org}/things";
    private static final String MESSAGE_MARKER = "MARK-MESSAGE-4e2b";
    private static final String HEADER_MARKER = "MARK-HEADER-4e2b";
    private static final String PARAM_MARKER = "MARK-PARAM-4e2b";

    /** Distinct classes, so the record can be checked to name the one that threw. */
    static final class ThrowingFilter implements AdmissionFilter {
        @Override public String name() { return "throws"; }

        @Override public AdmissionPhase phase() { return AdmissionPhase.AUTHENTICATION; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            throw new FilterFailure(MESSAGE_MARKER);
        }
    }

    static final class ThrowingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            throw new HandlerFailure(MESSAGE_MARKER);
        }
    }

    static final class FilterFailure extends RuntimeException {
        FilterFailure(String message) { super(message, new IllegalStateException(MESSAGE_MARKER)); }
    }

    static final class HandlerFailure extends RuntimeException {
        HandlerFailure(String message) { super(message, new IllegalStateException(MESSAGE_MARKER)); }
    }

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

    private static ScriptedExchange request() {
        return ScriptedExchange.of(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things",
                List.of(new HttpHeader("X-Marker", HEADER_MARKER)));
    }

    private void assertRecord(Class<?> component, Class<?> failure) {
        assertThat(log.at(Level.SEVERE)).hasSize(1);
        LogRecord record = log.at(Level.SEVERE).get(0);
        assertThat(record.getThrown()).isNull();
        String text = LogCapture.messageText(record);
        assertThat(text).contains(PATTERN).contains(component.getName()).contains(failure.getName());
        for (String marker : List.of(MESSAGE_MARKER, HEADER_MARKER, PARAM_MARKER)) {
            assertThat(text).doesNotContain(marker);
            assertThat(LogCapture.everything(record)).doesNotContain(marker);
        }
    }

    @Test
    @DisplayName("from a filter, through handle(): pattern, filter class and exception class, no throwable and no message")
    void filterThroughHandle() {
        registry.registerFilter(new ThrowingFilter());
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        ScriptedExchange exchange = request();

        registry.handle(exchange);

        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertRecord(ThrowingFilter.class, FilterFailure.class);
    }

    @Test
    @DisplayName("from a handler, through handle(): pattern, handler class and exception class, no throwable and no message")
    void handlerThroughHandle() {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), new ThrowingHandler()));
        ScriptedExchange exchange = request();

        registry.handle(exchange);

        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertRecord(ThrowingHandler.class, HandlerFailure.class);
    }

    @Test
    @DisplayName("from a filter, through execute(): the same record, and execute returns false after answering 500")
    void filterThroughExecute() {
        registry.registerFilter(new ThrowingFilter());
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        RouteMatch match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();
        ScriptedExchange exchange = request();

        boolean proceed = registry.execute(exchange, match);

        assertThat(proceed).isFalse();
        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertRecord(ThrowingFilter.class, FilterFailure.class);
    }

    @Test
    @DisplayName("a handler is never run by execute(), so its Exception has no execute() path")
    void executeDoesNotRunHandlers() {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), new ThrowingHandler()));
        RouteMatch match = registry.matchRoute(HttpMethod.GET, "/orgs/" + PARAM_MARKER + "/things").orElseThrow();

        assertThat(registry.execute(request(), match)).isTrue();

        assertThat(log.atOrAbove(Level.WARNING)).isEmpty();
    }
}
