package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A registry-originated answer whose write fails is logged once, or propagates when it is fatal")
class RegistryAnswerWriteFailureTest {

    private static final String PATTERN = "/orgs/{org}/things";
    private static final String PATH = "/orgs/acme/things";
    private static final String MESSAGE = "MSG-write-failed-77aa";

    /** Handler of its own class, so that a record naming it is recognisable. */
    static final class CountingHandler implements HttpHandler {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void handle(HttpExchange exchange) {
            runs.incrementAndGet();
            exchange.respond(HttpStatus.OK);
        }
    }

    /** Filter of its own class, for the same reason. */
    static final class DenyingFilter implements AdmissionFilter {
        private final java.util.function.Consumer<HttpExchange> body;

        DenyingFilter(java.util.function.Consumer<HttpExchange> body) {
            this.body = body;
        }

        @Override public String name() { return "denying"; }

        @Override public AdmissionPhase phase() { return AdmissionPhase.AUTHENTICATION; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            body.accept(exchange);
            return true;
        }
    }

    /** The kind of failure the write throws. */
    enum Failure {
        RUNTIME_EXCEPTION, ERROR, VIRTUAL_MACHINE_ERROR;

        Throwable create() {
            return switch (this) {
                case RUNTIME_EXCEPTION -> new IllegalArgumentException(MESSAGE);
                case ERROR -> new Probes.CustomError(MESSAGE);
                case VIRTUAL_MACHINE_ERROR -> new Probes.CustomVmError(MESSAGE);
            };
        }

        boolean fatal() {
            return this == VIRTUAL_MACHINE_ERROR;
        }
    }

    private DefaultApiSurfaceRegistry registry;
    private LogCapture log;
    private CountingHandler handler;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        log = new LogCapture(DefaultApiSurfaceRegistry.class);
        handler = new CountingHandler();
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    private void route(RouteRequirement requirement) {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t", List.of(requirement), handler));
    }

    private static Throwable leaving(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * What a failed registry answer must do: a fatal failure leaves the call as the very same instance, with
     * nothing logged; any other failure leaves nothing and is logged once at ERROR with the status, the
     * reason, the route pattern only when one matched, and the failure's class, never its message nor a
     * filter or handler class.
     */
    private void assertRule(Failure kind, Throwable thrown, Throwable failure, HttpStatus status, String reason,
                            boolean routeMatched) {
        if (kind.fatal()) {
            assertThat(thrown).isSameAs(failure);
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(log.at(Level.SEVERE)).isEmpty();
            return;
        }
        assertThat(thrown).isNull();
        assertThat(log.at(Level.SEVERE)).hasSize(1);
        LogRecord record = log.at(Level.SEVERE).get(0);
        assertThat(record.getThrown()).isNull();
        String text = LogCapture.everything(record);
        assertThat(text).contains(String.valueOf(status.code())).contains(reason)
                .contains(failure.getClass().getName())
                .doesNotContain(MESSAGE).doesNotContain("CountingHandler").doesNotContain("DenyingFilter")
                .doesNotContain("CountingFilter");
        if (routeMatched) {
            assertThat(text).contains(PATTERN);
        } else {
            assertThat(text).doesNotContain(PATTERN).doesNotContain("{org}");
        }
        assertThat(log.atOrAbove(Level.WARNING)).hasSize(1);
    }

    private static ScriptedExchange failing(HttpMethod method, Throwable failure) {
        return ScriptedExchange.of(method, PATH, List.of()).failOnRespondWith(failure);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("404 for a path that matches no route")
    void notFound(Failure kind) {
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        assertRule(kind, thrown, failure, HttpStatus.NOT_FOUND, "no route matches the path", false);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("405 with its Allow header for a path matched under another method; the pattern is not named")
    void methodNotAllowed(Failure kind) {
        route(RouteRequirement.permitAll());
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.POST, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.responses()).hasSize(1);
        assertThat(exchange.responses().get(0).headers()).anySatisfy(h -> {
            assertThat(h.name()).isEqualTo("Allow");
            assertThat(h.value()).isEqualTo("GET");
        });
        assertRule(kind, thrown, failure, HttpStatus.METHOD_NOT_ALLOWED, "method not allowed", false);
        assertThat(handler.runs).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("503 from admit, reached through execute() with the route matched: the pattern is named, execute returns false")
    void serviceUnavailableFromAdmit(Failure kind) {
        route(RouteRequirement.permitAll());
        RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
        registry.beginTeardown();
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);
        boolean[] proceed = {true};

        Throwable thrown = leaving(() -> proceed[0] = registry.execute(exchange, match));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        assertRule(kind, thrown, failure, HttpStatus.SERVICE_UNAVAILABLE, "tearing down", true);
        if (!kind.fatal()) {
            assertThat(proceed[0]).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("503 from handle() during teardown: no route is consulted, so no pattern is named")
    void serviceUnavailableFromHandle(Failure kind) {
        route(RouteRequirement.permitAll());
        registry.beginTeardown();
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        assertRule(kind, thrown, failure, HttpStatus.SERVICE_UNAVAILABLE, "tearing down", false);
        assertThat(handler.runs).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("401 for an unmet authentication requirement: the handler does not run after the failed denial")
    void unauthorized(Failure kind) {
        route(RouteRequirement.authenticated());
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
        assertRule(kind, thrown, failure, HttpStatus.UNAUTHORIZED, "unmet route requirement", true);
        assertThat(handler.runs).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("403 for a missing scope: the handler does not run after the failed denial")
    void forbidden(Failure kind) {
        registry.registerFilter(Probes.filter("auth", (ex, m) -> {
            AdmissionContext.current().authenticate(new Probes.Principal(Set.of("r")));
            return true;
        }));
        route(RouteRequirement.requiringAllScopes(Set.of("w")));
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertRule(kind, thrown, failure, HttpStatus.FORBIDDEN, "unmet route requirement", true);
        assertThat(handler.runs).hasValue(0);
    }

    private void tenantConflictFilter() {
        registry.registerFilter(new DenyingFilter(ex -> {
            throw new TenantConflictException();
        }));
        route(RouteRequirement.permitAll());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("tenant-conflict 403 from a filter, through handle(): the handler does not run")
    void tenantConflictFromFilterThroughHandle(Failure kind) {
        tenantConflictFilter();
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertRule(kind, thrown, failure, HttpStatus.FORBIDDEN, "tenant conflict", true);
        assertThat(handler.runs).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("tenant-conflict 403 from a filter, through execute(): execute returns false")
    void tenantConflictFromFilterThroughExecute(Failure kind) {
        tenantConflictFilter();
        RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);
        boolean[] proceed = {true};

        Throwable thrown = leaving(() -> proceed[0] = registry.execute(exchange, match));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertRule(kind, thrown, failure, HttpStatus.FORBIDDEN, "tenant conflict", true);
        if (!kind.fatal()) {
            assertThat(proceed[0]).isFalse();
        }
        assertThat(handler.runs).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("tenant-conflict 403 from the handler, through handle()")
    void tenantConflictFromHandler(Failure kind) {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), new HttpHandler() {
                    @Override
                    public void handle(HttpExchange exchange) {
                        throw new TenantConflictException();
                    }
                }));
        Throwable failure = kind.create();
        ScriptedExchange exchange = failing(HttpMethod.GET, failure);

        Throwable thrown = leaving(() -> registry.handle(exchange));

        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertRule(kind, thrown, failure, HttpStatus.FORBIDDEN, "tenant conflict", true);
    }
}
