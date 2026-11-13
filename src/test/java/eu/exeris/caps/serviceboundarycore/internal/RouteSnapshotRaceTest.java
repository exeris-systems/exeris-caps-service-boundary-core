package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A request matches and is answered against one table of routes, read once. A route registered or removed while
 * requests run is therefore seen by a request entirely or not at all, and a request never combines two tables.
 */
@DisplayName("A request is matched and answered against one table of routes")
class RouteSnapshotRaceTest {

    private static final int ROUNDS = 2_500;
    private static final int REQUEST_CAP = 400_000;

    private static final ExecutorService THREADS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "route-snapshot-race");
        thread.setDaemon(true);
        return thread;
    });

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    /** What one request was answered with. */
    private record Answer(HttpStatus status, String allow) {
        @Override
        public String toString() {
            return allow == null ? String.valueOf(status.code()) : status.code() + " Allow: " + allow;
        }
    }

    private static Answer ask(DefaultApiSurfaceRegistry registry, HttpMethod method, String path) {
        ScriptedExchange exchange = ScriptedExchange.of(method, path, List.of());
        registry.handle(exchange);
        assertThat(exchange.statuses()).hasSize(1);
        String allow = exchange.responses().isEmpty() ? null : exchange.responses().get(0).headers().stream()
                .filter(h -> h.name().equalsIgnoreCase("Allow")).map(HttpHeader::value).findFirst().orElse(null);
        return new Answer(exchange.statuses().get(0), allow);
    }

    /** Runs the writer and the requester at once, released together, and returns the distinct answers in order seen. */
    private static List<Answer> race(Runnable writer, DefaultApiSurfaceRegistry registry, HttpMethod method, String path,
                                     Predicate<Answer> last, long jitter) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> writing = THREADS.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            for (long i = 0; i < jitter; i++) {
                Thread.onSpinWait();
            }
            writer.run();
            return null;
        });
        Callable<List<Answer>> requesting = () -> {
            start.await(10, TimeUnit.SECONDS);
            List<Answer> seen = new ArrayList<>();
            for (int i = 0; i < REQUEST_CAP; i++) {
                Answer answer = ask(registry, method, path);
                if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(answer)) {
                    seen.add(answer);
                }
                if (last.test(answer)) {
                    return seen;
                }
            }
            throw new AssertionError("the request never saw its last answer; saw " + seen);
        };
        Future<List<Answer>> requested = THREADS.submit(requesting);
        List<Answer> seen = requested.get(60, TimeUnit.SECONDS);
        writing.get(10, TimeUnit.SECONDS);
        return seen;
    }

    private static final String LITERAL = "/r/lit";
    private static final String PARAMETERISED = "/r/{id}";

    private static ApiSurfaceRoute get(String pattern) {
        return Fixtures.open(pattern);
    }

    private static ApiSurfaceRoute post(String pattern) {
        return Fixtures.route(HttpMethod.POST, pattern, List.of(RouteRequirement.permitAll()), Fixtures.ok());
    }

    private static final Answer OK = new Answer(HttpStatus.OK, null);
    private static final Answer NOT_FOUND = new Answer(HttpStatus.NOT_FOUND, null);
    private static final Answer UNAVAILABLE = new Answer(HttpStatus.SERVICE_UNAVAILABLE, null);

    private static long jitter(SplittableRandom random) {
        return random.nextInt(6) == 0 ? 0 : random.nextInt(20_000);
    }

    // ---- registration ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a GET racing the registration of its own route is answered 404 and then 200, never 405")
    void registrationRaceNeverAnswers405() throws Exception {
        SplittableRandom random = new SplittableRandom(11);
        AtomicInteger sawNotFoundFirst = new AtomicInteger();
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                ApiSurfaceRoute route = get(pattern);
                List<Answer> seen = race(() -> registry.registerRoute(route), registry, HttpMethod.GET, "/r/lit",
                        a -> a.equals(OK), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(List.of(OK), List.of(NOT_FOUND, OK));
                if (seen.size() == 2) {
                    sawNotFoundFirst.incrementAndGet();
                }
            }
        }
        System.out.println("snapshot race: registration, " + sawNotFoundFirst.get() + " of " + 2 * ROUNDS
                + " rounds had requests on both sides of the registration");
    }

    @Test
    @DisplayName("a GET racing the registration of its route beside a POST route for the same path is answered 405 for POST only, then 200")
    void registrationRaceBesideAnotherMethodNeverNamesItsOwnMethod() throws Exception {
        SplittableRandom random = new SplittableRandom(12);
        Answer onlyPost = new Answer(HttpStatus.METHOD_NOT_ALLOWED, "POST");
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                registry.registerRoute(post(pattern));
                ApiSurfaceRoute route = get(pattern);
                List<Answer> seen = race(() -> registry.registerRoute(route), registry, HttpMethod.GET, "/r/lit",
                        a -> a.equals(OK), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(List.of(OK), List.of(onlyPost, OK));
            }
        }
    }

    // ---- removal ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a GET racing the removal of its route is answered 200 and then 404, never 405")
    void removalRaceNeverAnswers405() throws Exception {
        SplittableRandom random = new SplittableRandom(13);
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                ApiSurfaceRoute route = get(pattern);
                registry.registerRoute(route);
                List<Answer> seen = race(() -> assertThat(registry.unregisterRoute(route)).isTrue(), registry, HttpMethod.GET,
                        "/r/lit", a -> a.equals(NOT_FOUND), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(List.of(NOT_FOUND), List.of(OK, NOT_FOUND));
            }
        }
    }

    @Test
    @DisplayName("a GET racing the removal of its route beside a POST route for the same path is answered 200 and then 405 for POST only")
    void removalRaceBesideAnotherMethodNeverNamesItsOwnMethod() throws Exception {
        SplittableRandom random = new SplittableRandom(14);
        Answer onlyPost = new Answer(HttpStatus.METHOD_NOT_ALLOWED, "POST");
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                ApiSurfaceRoute route = get(pattern);
                registry.registerRoute(post(pattern));
                registry.registerRoute(route);
                List<Answer> seen = race(() -> registry.unregisterRoute(route), registry, HttpMethod.GET, "/r/lit",
                        a -> a.equals(onlyPost), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(List.of(onlyPost), List.of(OK, onlyPost));
            }
        }
    }

    // ---- teardown ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a GET racing a removal made after teardown began is answered 200 or 503, never 404")
    void removalAfterTeardownBeganIsAnsweredNormallyOrRefused() throws Exception {
        SplittableRandom random = new SplittableRandom(15);
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                ApiSurfaceRoute route = get(pattern);
                registry.registerRoute(route);
                List<Answer> seen = race(() -> {
                    registry.beginTeardown();
                    assertThat(registry.unregisterRoute(route)).isTrue();
                }, registry, HttpMethod.GET, "/r/lit", a -> a.equals(UNAVAILABLE), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(List.of(UNAVAILABLE), List.of(OK, UNAVAILABLE));
            }
        }
    }

    @Test
    @DisplayName("a GET racing teardown that follows a removal is answered 200, 404 and 503 in that order, each at most once")
    void teardownAfterRemovalIsAnsweredNormallyOrRefused() throws Exception {
        SplittableRandom random = new SplittableRandom(16);
        for (String pattern : new String[] {LITERAL, PARAMETERISED}) {
            for (int round = 0; round < ROUNDS; round++) {
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                ApiSurfaceRoute route = get(pattern);
                registry.registerRoute(route);
                List<Answer> seen = race(() -> {
                    assertThat(registry.unregisterRoute(route)).isTrue();
                    registry.beginTeardown();
                }, registry, HttpMethod.GET, "/r/lit", a -> a.equals(UNAVAILABLE), jitter(random));
                assertThat(seen).as("%s, round %d", pattern, round).isIn(
                        List.of(UNAVAILABLE), List.of(OK, UNAVAILABLE), List.of(NOT_FOUND, UNAVAILABLE),
                        List.of(OK, NOT_FOUND, UNAVAILABLE));
            }
        }
    }

    @Test
    @DisplayName("a request racing clear() is answered 200 and then 404, and its handler runs only when a request was answered 200")
    void clearRace() throws Exception {
        SplittableRandom random = new SplittableRandom(17);
        for (int round = 0; round < ROUNDS; round++) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            AtomicInteger ran = new AtomicInteger();
            registry.registerRoute(Fixtures.open("/r/{id}", ex -> {
                ran.incrementAndGet();
                ex.respond(HttpStatus.OK);
            }));
            List<Answer> seen = race(registry::clear, registry, HttpMethod.GET, "/r/lit", a -> a.equals(NOT_FOUND), jitter(random));
            assertThat(seen).isIn(List.of(NOT_FOUND), List.of(OK, NOT_FOUND));
            assertThat(ran.get() > 0).as("the handler ran exactly when a request was answered 200").isEqualTo(seen.contains(OK));
        }
    }

    // ---- what a caller can reach -----------------------------------------------------------------------------------

    private static DefaultApiSurfaceRegistry registryForImmutability() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(get("/lit"));
        registry.registerRoute(get("/one/{a}"));
        registry.registerRoute(get("/many/{a}/{b}/{c}/{d}/{e}/{f}"));
        return registry;
    }

    @Test
    @DisplayName("the parameters of a match cannot be changed, whatever the route, and a later request is matched as before")
    void matchedParametersAreImmutable() {
        DefaultApiSurfaceRegistry registry = registryForImmutability();
        String[][] requests = {{"/lit"}, {"/one/x"}, {"/many/1/2/3/4/5/6"}};
        for (String[] request : requests) {
            RouteMatch match = registry.matchRoute(HttpMethod.GET, request[0]).orElseThrow();
            Map<String, String> params = match.pathParams();
            assertThatThrownBy(() -> params.put("injected", "x")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(params::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> params.remove("a")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> params.replaceAll((k, v) -> "changed")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> params.entrySet().iterator().remove()).isInstanceOf(RuntimeException.class);
            assertThat(registry.matchRoute(HttpMethod.GET, request[0]).orElseThrow().pathParams()).isEqualTo(params);
        }
        assertThat(registry.matchRoute(HttpMethod.GET, "/many/1/2/3/4/5/6").orElseThrow().pathParams())
                .containsOnlyKeys("a", "b", "c", "d", "e", "f");
        assertThat(registry.matchRoute(HttpMethod.GET, "/injected")).isEmpty();
    }

    @Test
    @DisplayName("the parameters a handler reads from its exchange cannot be changed, and the next request does not see an attempt")
    void parametersSeenByAHandlerAreImmutable() {
        List<Map<String, String>> seen = new ArrayList<>();
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/o/{id}", ex -> {
            seen.add(ex.pathParams());
            ex.respond(HttpStatus.OK);
        }));
        registry.handle(ScriptedExchange.get("/o/1"));
        assertThatThrownBy(() -> seen.get(0).put("id", "2")).isInstanceOf(UnsupportedOperationException.class);
        registry.handle(ScriptedExchange.get("/o/3"));
        assertThat(seen).containsExactly(Map.of("id", "1"), Map.of("id", "3"));
    }

    @Test
    @DisplayName("the list of routes is a snapshot that cannot be changed, and a route's own lists cannot be changed")
    void routesListAndRouteListsAreImmutable() {
        DefaultApiSurfaceRegistry registry = registryForImmutability();
        List<ApiSurfaceRoute> before = registry.routes();
        assertThatThrownBy(() -> before.add(get("/injected"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(before::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.set(0, get("/injected"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.routeCount()).isEqualTo(3);
        assertThat(registry.matchRoute(HttpMethod.GET, "/injected")).isEmpty();

        registry.registerRoute(get("/later"));
        assertThat(before).as("a list taken earlier does not follow the registry").hasSize(3);
        assertThat(registry.routes()).hasSize(4);

        ApiSurfaceRoute route = registry.routes().get(2);
        assertThatThrownBy(() -> route.paramNames().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> route.paramNames().set(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> route.requirements().add(RouteRequirement.permitAll())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PathPattern("/x/{a}").names().add("b")).isInstanceOf(UnsupportedOperationException.class);
        Map<String, String> extracted = route.extractPathParams("/many/1/2/3/4/5/6");
        assertThatThrownBy(() -> extracted.put("g", "7")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.matchRoute(HttpMethod.GET, "/many/1/2/3/4/5/6")).isPresent();
    }

    @Test
    @DisplayName("a table already read by a request is unaffected by a later registration, and one request is never answered from two tables")
    void aRegistrationDuringARequestDoesNotChangeItsAnswer() {
        Set<Answer> answers = new LinkedHashSet<>();
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        ApiSurfaceRoute late = get("/during/{id}");
        // the requester is a handler of an earlier route that registers a route and then asks again
        registry.registerRoute(Fixtures.open("/trigger", ex -> {
            registry.registerRoute(late);
            ex.respond(HttpStatus.OK);
        }));
        answers.add(ask(registry, HttpMethod.GET, "/during/1"));
        answers.add(ask(registry, HttpMethod.GET, "/trigger"));
        answers.add(ask(registry, HttpMethod.GET, "/during/1"));
        assertThat(answers).containsExactly(NOT_FOUND, OK);
        assertThat(registry.routeCount()).isEqualTo(2);
    }
}
