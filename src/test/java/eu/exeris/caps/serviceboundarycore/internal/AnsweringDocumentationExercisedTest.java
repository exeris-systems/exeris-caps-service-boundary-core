package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.ClaimingExchange;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The README and Javadoc statements about what "answered" means, about a failed registry answer and about a
 * denial that cannot be written: each one is read from the source text and then exercised.
 */
@DisplayName("Documented answering contract: the text says it, the registry does it")
class AnsweringDocumentationExercisedTest {

    private static final String PATTERN = "/orgs/{org}/things";
    private static final String PATH = "/orgs/acme/things";

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

    private static String text(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("(?m)^\\s*\\*? ?", "").replaceAll("\\s+", " ");
    }

    private void route() {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> {
                    handlerRuns.incrementAndGet();
                    ex.respond(HttpStatus.OK);
                }));
    }

    @Test
    @DisplayName("README: an exchange is answered when a respond returned, and the registry never answers an answered exchange again")
    void answeredIsNeverAnsweredAgain() throws IOException {
        assertThat(text("README.md")).contains(
                "An exchange is answered when a `respond` call on it returned normally, and the registry never answers an answered exchange a second time.");
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> {
                    ex.respond(HttpStatus.OK);
                    throw new IllegalArgumentException("after the answer");
                }));
        ScriptedExchange exchange = ScriptedExchange.get(PATH);

        registry.handle(exchange);

        assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
    }

    @Test
    @DisplayName("README: a respond that threw has not answered, so the registry answers 500 once; if the kernel had claimed, the refusal is logged")
    void failedRespondIsAnsweredOnce() throws IOException {
        assertThat(text("README.md")).contains("A `respond` call that threw has not answered the exchange")
                .contains("so the registry answers 500 once");
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> {
                    try {
                        ex.respond(HttpStatus.OK);
                    } catch (RuntimeException swallowed) {
                        // the handler gives up
                    }
                }));
        ClaimingExchange exchange = new ClaimingExchange(HttpMethod.GET, PATH, new IllegalArgumentException("encode"));

        registry.handle(exchange);

        assertThat(exchange.attempts()).containsExactly(HttpStatus.OK, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(log.at(Level.SEVERE)).hasSize(1);
    }

    @Test
    @DisplayName("README and AdmissionFilter: a filter that tried to answer and failed is logged once at WARNING naming the pattern and the filter class")
    void failedAttemptWarning() throws IOException {
        assertThat(text("README.md")).contains(
                "a filter that tried to answer and failed is logged once at WARNING in the same way");
        assertThat(text("src/main/java/eu/exeris/caps/serviceboundarycore/api/AdmissionFilter.java"))
                .contains("If that attempt threw, the exchange is not answered and the registry answers 500 once.");
        CountingFilter filter = new CountingFilter("f", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            try {
                ex.respond(HttpStatus.FORBIDDEN);
            } catch (RuntimeException swallowed) {
                // the filter gives up
            }
            return true;
        });
        registry.registerFilter(filter);
        route();
        ScriptedExchange exchange = ScriptedExchange.get(PATH)
                .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("encode"));

        registry.handle(exchange);

        List<LogRecord> warnings = log.at(Level.WARNING);
        assertThat(warnings).hasSize(1);
        assertThat(LogCapture.everything(warnings.get(0))).contains(PATTERN).contains(CountingFilter.class.getName());
        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handlerRuns).hasValue(0);
    }

    @Test
    @DisplayName("README and AdmissionFilter: a filter that answered and returned true is logged once at WARNING, and admission ends")
    void answeredAndTrueWarning() throws IOException {
        assertThat(text("README.md")).contains(
                "A filter that answered and returned `true` is a contract violation and is logged once at WARNING with the route's path pattern and the filter's class");
        assertThat(text("src/main/java/eu/exeris/caps/serviceboundarycore/api/AdmissionFilter.java"))
                .contains("A filter that answered and returned {@code true} violates this contract and is logged once at WARNING");
        registry.registerFilter(new CountingFilter("f", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            ex.respond(HttpStatus.FORBIDDEN);
            return true;
        }));
        route();
        ScriptedExchange exchange = ScriptedExchange.get(PATH);

        registry.handle(exchange);

        assertThat(log.at(Level.WARNING)).hasSize(1);
        assertThat(LogCapture.everything(log.at(Level.WARNING).get(0))).contains(PATTERN)
                .contains(CountingFilter.class.getName());
        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertThat(handlerRuns).hasValue(0);
    }

    @Test
    @DisplayName("AdmissionFilter: a second respond after one returned is refused by the exchange, and the registry adds no guard of its own")
    void secondRespondIsTheKernelsRefusal() throws IOException {
        assertThat(text("src/main/java/eu/exeris/caps/serviceboundarycore/api/AdmissionFilter.java"))
                .contains("the kernel's exchange refuses it with {@link IllegalStateException}");
        Throwable[] seen = new Throwable[1];
        registry.registerFilter(new CountingFilter("f", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            ex.respond(HttpStatus.FORBIDDEN);
            try {
                ex.respond(HttpStatus.FORBIDDEN);
            } catch (IllegalStateException refused) {
                seen[0] = refused;
            }
            return false;
        }));
        route();
        ScriptedExchange exchange = ScriptedExchange.get(PATH).refuseSecondAnswer();

        registry.handle(exchange);

        assertThat(seen[0]).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        assertThat(handlerRuns).hasValue(0);
    }

    @Test
    @DisplayName("MatchedHttpExchange: responded is true exactly when a respond returned, attempted after any call, an answer is never downgraded")
    void matchedHttpExchangeJavadoc() throws IOException {
        String javadoc = text("src/main/java/eu/exeris/caps/serviceboundarycore/internal/MatchedHttpExchange.java");
        assertThat(javadoc)
                .contains("{@link #responded()} is true exactly then, and never otherwise.")
                .contains("{@link #attempted()} is true after any call.")
                .contains("An answer is never downgraded: a refused second call leaves an answered exchange answered.")
                .contains("recording an answer costs one volatile write and reading both costs one volatile read");
        // Exercised at unit level by MatchedHttpExchangeStateTest; here the registry-visible consequence.
        MatchedHttpExchange exchange = new MatchedHttpExchange(ScriptedExchange.get(PATH).refuseSecondAnswer(),
                java.util.Map.of());
        exchange.respond(HttpStatus.OK);
        assertThatCode(() -> exchange.respond(HttpStatus.OK)).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.responded()).isTrue();
    }

    @Test
    @DisplayName("README: a denial whose write fails never falls through to the handler, at every denial site, and nothing propagates")
    void failedDenialNeverFallsThrough() throws IOException {
        String readme = text("README.md");
        assertThat(readme).contains("A request whose denial could not be written is finished: the handler does not run.")
                .contains("so no failure leaves it unlogged except an `Error` being handled, which it rethrows");
        IllegalArgumentException failure = new IllegalArgumentException("write");

        // 401
        route401();
        ScriptedExchange unauthorized = ScriptedExchange.get(PATH).failOnRespondWith(failure);
        assertThatCode(() -> registry.handle(unauthorized)).doesNotThrowAnyException();
        assertThat(handlerRuns).hasValue(0);
        assertThat(log.at(Level.SEVERE)).hasSize(1);
    }

    private void route401() {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATTERN, "T#t",
                List.of(RouteRequirement.authenticated()), ex -> {
                    handlerRuns.incrementAndGet();
                    ex.respond(HttpStatus.OK);
                }));
    }

    @Test
    @DisplayName("README: the record of a failed registry answer names status and reason, the pattern, the class; no message")
    void recordContent() throws IOException {
        assertThat(text("README.md")).contains(
                "The record names the status, what was being answered (for instance the 401 for an unmet route requirement), the route's path pattern when a route matched, and the failure's class")
                .contains("and no message");
        route401();
        ScriptedExchange exchange = ScriptedExchange.get(PATH)
                .failOnRespondWith(new IllegalArgumentException("SECRET-message"));

        registry.handle(exchange);

        String record = LogCapture.everything(log.at(Level.SEVERE).get(0));
        assertThat(record).contains("401").contains("unmet route requirement").contains(PATTERN)
                .contains(IllegalArgumentException.class.getName()).doesNotContain("SECRET-message");
    }

    @Test
    @DisplayName("README: a VirtualMachineError thrown by the answering attempt always propagates")
    void vmErrorPropagates() throws IOException {
        assertThat(text("README.md")).contains("A `VirtualMachineError` thrown by the answering attempt always propagates.");
        route401();
        Probes.CustomVmError fatal = new Probes.CustomVmError("fatal");
        ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(fatal);

        Throwable thrown = null;
        try {
            registry.handle(exchange);
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown).isSameAs(fatal);
        assertThat(handlerRuns).hasValue(0);
    }

    @Test
    @DisplayName("README: a filter that attempted to answer ends admission through execute() too, which returns false")
    void executeFollowsTheSameRule() throws IOException {
        assertThat(text("README.md")).contains("`RequestFilterChain.execute(...)` follows the same rule for the answers it writes");
        registry.registerFilter(new CountingFilter("f", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            ex.respond(HttpStatus.FORBIDDEN);
            return true;
        }));
        route();
        RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();

        assertThat(registry.execute(ScriptedExchange.get(PATH), match)).isFalse();
    }
}
