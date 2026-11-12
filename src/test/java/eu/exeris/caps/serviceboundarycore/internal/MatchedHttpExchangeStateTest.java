package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MatchedHttpExchange keeps one state: none, attempted without an answer, answered")
class MatchedHttpExchangeStateTest {

    /** The four {@code respond} overloads. */
    enum Overload {
        RESPONSE(e -> e.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_1_1))),
        TYPED(e -> e.respond(HttpTypedResponse.of(HttpStatus.OK, "payload"))),
        STATUS_AND_BODY(e -> e.respond(HttpStatus.OK, "payload")),
        STATUS(e -> e.respond(HttpStatus.OK));

        private final Consumer<HttpExchange> call;

        Overload(Consumer<HttpExchange> call) {
            this.call = call;
        }

        void on(HttpExchange exchange) {
            call.accept(exchange);
        }
    }

    private static MatchedHttpExchange wrap(ScriptedExchange delegate) {
        return new MatchedHttpExchange(delegate, Map.of());
    }

    @Test
    @DisplayName("a fresh wrapper is neither attempted nor answered")
    void freshIsNone() {
        MatchedHttpExchange exchange = wrap(ScriptedExchange.get("/x"));

        assertThat(exchange.attempted()).isFalse();
        assertThat(exchange.responded()).isFalse();
    }

    @ParameterizedTest(name = "{0} returning")
    @EnumSource(Overload.class)
    @DisplayName("a respond that returns leaves the wrapper attempted and answered")
    void returningIsAnswered(Overload overload) {
        ScriptedExchange delegate = ScriptedExchange.get("/x");
        MatchedHttpExchange exchange = wrap(delegate);

        overload.on(exchange);

        assertThat(exchange.attempted()).isTrue();
        assertThat(exchange.responded()).isTrue();
        assertThat(delegate.respondCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} throwing")
    @EnumSource(Overload.class)
    @DisplayName("a respond that throws leaves the wrapper attempted and not answered, and the throwable is the delegate's")
    void throwingIsAttemptedOnly(Overload overload) {
        IllegalArgumentException failure = new IllegalArgumentException("cannot encode");
        ScriptedExchange delegate = ScriptedExchange.get("/x").failOnRespondWith(failure);
        MatchedHttpExchange exchange = wrap(delegate);

        assertThatThrownBy(() -> overload.on(exchange)).isSameAs(failure);

        assertThat(exchange.attempted()).isTrue();
        assertThat(exchange.responded()).isFalse();
    }

    @ParameterizedTest(name = "{0} throwing an Error")
    @EnumSource(Overload.class)
    @DisplayName("a respond that throws an Error is attempted and not answered")
    void throwingErrorIsAttemptedOnly(Overload overload) {
        Probes.CustomError failure = new Probes.CustomError("broken");
        ScriptedExchange delegate = ScriptedExchange.get("/x").failOnRespondWith(failure);
        MatchedHttpExchange exchange = wrap(delegate);

        assertThatThrownBy(() -> overload.on(exchange)).isSameAs(failure);

        assertThat(exchange.attempted()).isTrue();
        assertThat(exchange.responded()).isFalse();
    }

    @ParameterizedTest(name = "answer through {0}, then a refused second respond")
    @EnumSource(Overload.class)
    @DisplayName("a throw after an answer leaves the exchange answered")
    void throwAfterAnswerStaysAnswered(Overload overload) {
        ScriptedExchange delegate = ScriptedExchange.get("/x").refuseSecondAnswer();
        MatchedHttpExchange exchange = wrap(delegate);
        overload.on(exchange);

        for (Overload second : Overload.values()) {
            assertThatThrownBy(() -> second.on(exchange)).isInstanceOf(IllegalStateException.class);
            assertThat(exchange.responded()).as("after a refused %s", second).isTrue();
            assertThat(exchange.attempted()).isTrue();
        }
        assertThat(delegate.respondCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Overload.class)
    @DisplayName("two failing attempts stay attempted and not answered")
    void twoFailuresStayAttempted(Overload overload) {
        ScriptedExchange delegate = ScriptedExchange.get("/x").failOnRespondWith(new IllegalArgumentException("no"));
        MatchedHttpExchange exchange = wrap(delegate);

        assertThatThrownBy(() -> overload.on(exchange)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overload.on(exchange)).isInstanceOf(IllegalArgumentException.class);

        assertThat(exchange.attempted()).isTrue();
        assertThat(exchange.responded()).isFalse();
    }

    @Test
    @DisplayName("a failed attempt followed by one that returns ends answered")
    void failureThenSuccess() {
        ScriptedExchange delegate = ScriptedExchange.get("/x")
                .failFirstRespondBeforeRecordingWith(new IllegalArgumentException("encoder"));
        MatchedHttpExchange exchange = wrap(delegate);

        assertThatThrownBy(() -> exchange.respond(HttpStatus.OK)).isInstanceOf(IllegalArgumentException.class);
        assertThat(exchange.responded()).isFalse();
        exchange.respond(HttpStatus.OK);

        assertThat(exchange.responded()).isTrue();
        assertThat(delegate.respondCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two threads racing a first respond against an exchange that refuses the second: one answer, never downgraded")
    void concurrentFirstResponds() throws Exception {
        int rounds = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier end = new CyclicBarrier(3);
        try {
            for (int round = 0; round < rounds; round++) {
                ScriptedExchange delegate = ScriptedExchange.get("/x").refuseSecondAnswer();
                MatchedHttpExchange exchange = wrap(delegate);
                Runnable racer = () -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            exchange.respond(HttpStatus.OK);
                        } catch (IllegalStateException refused) {
                            // the loser of the claim
                        }
                        end.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
                Future<?> a = pool.submit(racer);
                Future<?> b = pool.submit(racer);
                end.await(10, TimeUnit.SECONDS);
                a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);

                assertThat(delegate.respondCount()).as("round %d: answers recorded", round).isEqualTo(1);
                assertThat(exchange.responded()).as("round %d: answered, not downgraded", round).isTrue();
                assertThat(exchange.attempted()).as("round %d", round).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
