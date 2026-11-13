package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ReferencePathPattern;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The cost of a request that does not match. A request path is chosen by the caller and a pattern is not, so the
 * growth of the cost with the length of the path is what matters. Figures are printed; the only bounds asserted are
 * ones that hold by orders of magnitude on any machine.
 */
@DisplayName("Cost of requests that do not match: adjacent parameters and very long paths")
class RouteMatchingWorstCaseTest {

    /** The fastest of several runs, in milliseconds, after warm-up runs. */
    private static double millis(Runnable work) {
        for (int i = 0; i < 3; i++) {
            work.run();
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            work.run();
            best = Math.min(best, (System.nanoTime() - start) / 1e6);
        }
        return best;
    }

    private static String repeat(String unit, int count) {
        return unit.repeat(count);
    }

    private static String line(String what, int[] sizes, double[] production, double[] reference) {
        StringBuilder out = new StringBuilder("worst case finding: ").append(what);
        for (int i = 0; i < sizes.length; i++) {
            out.append(String.format(Locale.ROOT, "%n    n=%d: route %.3f ms, reference pattern %.3f ms", sizes[i], production[i],
                    reference[i]));
            if (i > 0) {
                out.append(String.format(Locale.ROOT, "; route grew x%.1f for n x%.1f (degree %.2f)",
                        production[i] / production[i - 1], (double) sizes[i] / sizes[i - 1],
                        Math.log(production[i] / production[i - 1]) / Math.log((double) sizes[i] / sizes[i - 1])));
            }
        }
        return out.toString();
    }

    private static void measure(String what, String pattern, int[] sizes, java.util.function.IntFunction<String> pathOf) {
        ApiSurfaceRoute route = Fixtures.open(pattern);
        ReferencePathPattern reference = new ReferencePathPattern(pattern);
        double[] production = new double[sizes.length];
        double[] ref = new double[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            String path = pathOf.apply(sizes[i]);
            production[i] = millis(() -> assertThat(route.matchesPath(path)).isFalse());
            ref[i] = millis(() -> assertThat(reference.matches(path)).isFalse());
        }
        System.out.println(line(what, sizes, production, ref));
    }

    @Test
    @DisplayName("finding: adjacent parameters backtrack in polynomial time, of the degree of the number of adjacent parameters, as the reference pattern does")
    void adjacentParametersBacktrackPolynomially() {
        measure("pattern /{a}{b}{c}{d}{e}{f}x, a path segment of n characters without the final x",
                "/{a}{b}{c}{d}{e}{f}x", new int[] {15, 30, 60}, n -> "/" + repeat("a", n));
        measure("pattern /{a}{b}{c}x, a path segment of n characters without the final x",
                "/{a}{b}{c}x", new int[] {30, 60, 120, 240, 480}, n -> "/" + repeat("a", n));
        measure("pattern /{a}-{b}x, a path of n dashes without the final x",
                "/{a}-{b}x", new int[] {1000, 2000, 4000, 8000}, n -> "/" + repeat("-", n));
    }

    @Test
    @DisplayName("three adjacent parameters and a path of 480 characters without the final literal finish within seconds, and the growth per doubling stays polynomial")
    void threeAdjacentParametersAreBounded() {
        ApiSurfaceRoute route = Fixtures.open("/{a}{b}{c}x");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            double at120 = millis(() -> assertThat(route.matchesPath("/" + repeat("a", 120))).isFalse());
            double at240 = millis(() -> assertThat(route.matchesPath("/" + repeat("a", 240))).isFalse());
            double at480 = millis(() -> assertThat(route.matchesPath("/" + repeat("a", 480))).isFalse());
            assertThat(at480).as("milliseconds for 480 characters").isLessThan(20_000.0);
            // a cubic cost grows by 8 per doubling; anything exponential grows by more than 2^100
            assertThat(at480 / Math.max(at240, 0.05)).isLessThan(500.0);
            assertThat(at240 / Math.max(at120, 0.05)).isLessThan(500.0);
        });
    }

    // ---- a path of 64 KiB ---------------------------------------------------------------------------------------

    private static final int KIB64 = 64 * 1024;

    private static ScriptedExchange serve(DefaultApiSurfaceRegistry registry, String path) {
        ScriptedExchange exchange = ScriptedExchange.get(path);
        registry.handle(exchange);
        return exchange;
    }

    private static DefaultApiSurfaceRegistry registryWithLiteralAndParameterisedRoutes(AtomicReference<Map<String, String>> seen) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/api/health"));
        registry.registerRoute(Fixtures.open("/api/{id}", ex -> {
            seen.set(ex.pathParams());
            ex.respond(HttpStatus.OK);
        }));
        registry.registerRoute(Fixtures.open("/files/{name}.{ext}"));
        registry.registerRoute(Fixtures.open("/{a}/{b}/{c}"));
        return registry;
    }

    private static void within(Duration limit, Runnable work) {
        assertTimeoutPreemptively(limit, work::run);
    }

    @Test
    @DisplayName("a path of 64 KiB is answered by literal and parameterised routes, matched or not, in well under a second")
    void aPathOf64KibIsAnsweredQuickly() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        DefaultApiSurfaceRegistry registry = registryWithLiteralAndParameterisedRoutes(seen);
        String longValue = repeat("v", KIB64 - 5);

        List<String> figures = new ArrayList<>();
        Supplier<ScriptedExchange> hit = () -> serve(registry, "/api/" + longValue);
        within(Duration.ofSeconds(10), () -> {
            ScriptedExchange exchange = hit.get();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
            assertThat(seen.get()).containsOnlyKeys("id");
            assertThat(seen.get().get("id")).hasSize(longValue.length()).isEqualTo(longValue);
            figures.add(String.format(Locale.ROOT, "hit %.3f ms", millis(hit::get)));
        });
        within(Duration.ofSeconds(10), () -> {
            assertThat(serve(registry, "/" + repeat("z", KIB64)).statuses()).containsExactly(HttpStatus.NOT_FOUND);
            assertThat(serve(registry, "/api/health" + repeat("z", KIB64)).statuses()).containsExactly(HttpStatus.OK);
            assertThat(serve(registry, "/" + repeat("/", KIB64)).statuses()).containsExactly(HttpStatus.NOT_FOUND);
            assertThat(serve(registry, "/a/b/" + repeat("c", KIB64)).statuses()).containsExactly(HttpStatus.OK);
            assertThat(serve(registry, "/a/b/" + repeat("c", KIB64) + "/d").statuses()).containsExactly(HttpStatus.NOT_FOUND);
            assertThat(serve(registry, "/files/" + repeat("n", KIB64)).statuses())
                    .as("no dot: no route matches, in time linear in the path").containsExactly(HttpStatus.NOT_FOUND);
            assertThat(serve(registry, "/files/" + repeat("n", KIB64) + ".txt").statuses()).containsExactly(HttpStatus.OK);
        });
        System.out.println("worst case finding: a 64 KiB path against literal and parameterised routes: " + figures);
    }

    @Test
    @DisplayName("a long query string after a short path adds a cost of the same order as the path alone")
    void aLongQueryStringDoesNotMultiplyTheCost() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/{a}-{b}x"));
        String path = "/" + repeat("-", 1500);
        String withQuery = path + "?" + repeat("q", KIB64 - path.length());
        double plain = millis(() -> serve(registry, path));
        double long64 = millis(() -> serve(registry, withQuery));
        System.out.println(String.format(Locale.ROOT, "worst case finding: 1500 dashes, %.2f ms; with a %d character query string, %.2f ms",
                plain, KIB64 - path.length(), long64));
        assertThat(serve(registry, withQuery).statuses()).containsExactly(HttpStatus.NOT_FOUND);
        assertThat(long64).as("the query string is not part of the matched path").isLessThan(Math.max(plain * 20, 200.0));
    }
}
