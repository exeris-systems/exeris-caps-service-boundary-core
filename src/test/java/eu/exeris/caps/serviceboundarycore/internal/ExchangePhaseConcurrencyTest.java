package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The answer rule across registry phases")
class ExchangePhaseConcurrencyTest {

    private static final String PATH = "/p";
    private static final int ROUNDS = 3000;

    private static DefaultApiSurfaceRegistry registryWith(HttpHandler handler, Probes.FilterBody filterBody) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "P#get",
                List.of(RouteRequirement.permitAll()), handler));
        if (filterBody != null) {
            registry.registerFilter(new Probes.CountingFilter("f", AdmissionPhase.AUTHENTICATION, filterBody));
        }
        return registry;
    }

    @Test
    @DisplayName("while frozen, an unanswered handler is answered 500")
    void frozenHandlerUnanswered() {
        DefaultApiSurfaceRegistry registry = registryWith(ex -> { }, null);
        registry.freeze();
        ScriptedExchange exchange = ScriptedExchange.get(PATH);

        registry.handle(exchange);

        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("while frozen, a filter that returns false without answering is answered 500 and the handler does not run")
    void frozenFilterUnanswered() {
        boolean[] ran = new boolean[1];
        DefaultApiSurfaceRegistry registry = registryWith(ex -> ran[0] = true, (ex, m) -> false);
        registry.freeze();
        ScriptedExchange exchange = ScriptedExchange.get(PATH);

        registry.handle(exchange);

        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ran[0]).isFalse();
    }

    @Test
    @DisplayName("while frozen, an Error is answered 500 and the same Error is rethrown")
    void frozenError() {
        Probes.CustomError error = new Probes.CustomError("e");
        DefaultApiSurfaceRegistry registry = registryWith(ex -> {
            throw error;
        }, null);
        registry.freeze();
        ScriptedExchange exchange = ScriptedExchange.get(PATH);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);
        assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("during teardown every request is answered 503 once, whatever the filter or handler would have done")
    void teardownAnswers503Only() {
        boolean[] ran = new boolean[2];
        DefaultApiSurfaceRegistry registry = registryWith(ex -> ran[0] = true, (ex, m) -> {
            ran[1] = true;
            return false;
        });
        registry.freeze();
        registry.beginTeardown();

        ScriptedExchange matched = ScriptedExchange.get(PATH);
        ScriptedExchange unmatched = ScriptedExchange.get("/nothing");
        registry.handle(matched);
        registry.handle(unmatched);

        assertThat(matched.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unmatched.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ran).containsExactly(false, false);
    }

    @Test
    @DisplayName("a request already inside the handler when teardown begins keeps its own answer and gets no 503")
    void inFlightRequestKeepsItsAnswer() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultApiSurfaceRegistry registry = registryWith(ex -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.respond(HttpStatus.OK);
        }, null);
        registry.freeze();
        ScriptedExchange exchange = ScriptedExchange.get(PATH);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = pool.submit(() -> registry.handle(exchange));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            registry.beginTeardown();
            release.countDown();
            request.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
    }

    @Test
    @DisplayName("a request racing beginTeardown gets exactly one answer, its normal one or 503 (stress)")
    void answeredHandlerRacingTeardown() throws Exception {
        assertOneAnswerEach(ex -> ex.respond(HttpStatus.OK), null, Set.of(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("a request whose handler stays silent, racing beginTeardown, gets exactly one answer: 500 or 503 (stress)")
    void silentHandlerRacingTeardown() throws Exception {
        assertOneAnswerEach(ex -> { }, null,
                Set.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("a request whose filter halts silently, racing beginTeardown, gets exactly one answer: 500 or 503 (stress)")
    void silentFilterRacingTeardown() throws Exception {
        assertOneAnswerEach(ex -> ex.respond(HttpStatus.OK), (ex, m) -> false,
                Set.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("a request whose filter denies, racing beginTeardown, gets exactly one answer: 429 or 503 (stress)")
    void denyingFilterRacingTeardown() throws Exception {
        assertOneAnswerEach(ex -> ex.respond(HttpStatus.OK), (ex, m) -> {
            ex.respond(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }, Set.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("a request through execute() racing beginTeardown gets at most one answer, and 503 only when execute returns false (stress)")
    void executeRacingTeardown() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = registryWith(ex -> ex.respond(HttpStatus.OK),
                        (ex, m) -> false);
                var match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
                registry.freeze();
                CyclicBarrier barrier = new CyclicBarrier(2);
                ScriptedExchange exchange = ScriptedExchange.get(PATH);
                Future<Boolean> request = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return registry.execute(exchange, match);
                });
                Future<?> teardown = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    registry.beginTeardown();
                    return null;
                });
                boolean proceed = request.get(10, TimeUnit.SECONDS);
                teardown.get(10, TimeUnit.SECONDS);

                assertThat(proceed).isFalse();
                assertThat(exchange.statuses()).hasSize(1);
                assertThat(exchange.statuses().get(0))
                        .isIn(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOneAnswerEach(HttpHandler handler, Probes.FilterBody filterBody, Set<HttpStatus> allowed)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Set<HttpStatus> seen = new java.util.HashSet<>();
        List<String> violations = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = registryWith(handler, filterBody);
                registry.freeze();
                CyclicBarrier barrier = new CyclicBarrier(2);
                ScriptedExchange exchange = ScriptedExchange.get(PATH);
                Future<?> request = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    registry.handle(exchange);
                    return null;
                });
                Future<?> teardown = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    registry.beginTeardown();
                    return null;
                });
                request.get(10, TimeUnit.SECONDS);
                teardown.get(10, TimeUnit.SECONDS);

                List<HttpStatus> statuses = exchange.statuses();
                if (statuses.size() != 1 || !allowed.contains(statuses.get(0))) {
                    violations.add("round " + round + ": " + statuses);
                } else {
                    seen.add(statuses.get(0));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(violations).as("rounds that were not answered exactly once with an allowed status").isEmpty();
        assertThat(seen).isNotEmpty();
    }
}
