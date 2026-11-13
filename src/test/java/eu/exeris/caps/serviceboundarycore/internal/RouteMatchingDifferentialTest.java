package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ReferencePathPattern;
import eu.exeris.caps.serviceboundarycore.testsupport.RouteGenerator;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every pattern and path pair is decided twice: by the route and the registry, and by the reference pattern, which
 * is the documented regular expression ({@code ([^/]+)} for a parameter, literal texts quoted) applied to the
 * normalised path. The two must agree on whether the path matches and on every parameter value.
 */
@DisplayName("Route matching agrees with the reference pattern on generated patterns and paths")
class RouteMatchingDifferentialTest {

    private static final int CHUNKS = 24;
    private static final int PATTERNS_PER_CHUNK = 1_000;
    private static final int PATHS_PER_PATTERN = 100;
    private static final long PAIRS = (long) CHUNKS * PATTERNS_PER_CHUNK * PATHS_PER_PATTERN;
    private static final int SAMPLES_PER_KIND = 6;
    /** A pattern whose literal text ends with a high surrogate before a parameter or begins with a low one after it. */
    private static final java.util.regex.Pattern HALF_PAIR_AT_PARAMETER = java.util.regex.Pattern.compile(
            "[\\uD800-\\uDBFF]\\{[a-zA-Z0-9_]+\\}|\\{[a-zA-Z0-9_]+\\}[\\uDC00-\\uDFFF]");

    private static final class Tally {
        final LongAdder pairs = new LongAdder();
        final LongAdder matched = new LongAdder();
        final LongAdder matchedWithParameters = new LongAdder();
        final LongAdder matchedWithSeveralParameters = new LongAdder();
        final LongAdder matchedRegistry = new LongAdder();
        final LongAdder shortLived = new LongAdder();
        final Map<String, LongAdder> disagreements = new ConcurrentHashMap<>();
        final Map<String, ConcurrentLinkedQueue<String>> samples = new ConcurrentHashMap<>();

        void disagree(String kind, String pattern, String path, String expected, String actual) {
            kind = (HALF_PAIR_AT_PARAMETER.matcher(pattern).find() ? "[pattern splits a pair at a parameter] " : "[other] ") + kind;
            disagreements.computeIfAbsent(kind, k -> new LongAdder()).increment();
            ConcurrentLinkedQueue<String> queue = samples.computeIfAbsent(kind, k -> new ConcurrentLinkedQueue<>());
            if (queue.size() < SAMPLES_PER_KIND) {
                queue.add("pattern " + ReferencePathPattern.show(pattern) + " path " + ReferencePathPattern.show(path)
                        + " reference " + expected + " actual " + actual);
            }
        }

        String report() {
            StringBuilder out = new StringBuilder();
            new TreeMap<>(disagreements).forEach((kind, count) -> {
                out.append('\n').append(kind).append(": ").append(count.sum()).append(" disagreements");
                samples.get(kind).forEach(sample -> out.append("\n    ").append(sample));
            });
            return out.toString();
        }
    }

    private static String describe(Map<String, String> map) {
        return map == null ? "no match" : ReferencePathPattern.show(map.toString());
    }

    private static void compareOnePattern(RouteGenerator generator, Tally tally) {
        String pattern = generator.pattern();
        ReferencePathPattern reference = new ReferencePathPattern(pattern);
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        ApiSurfaceRoute route = Fixtures.open(pattern, ex -> {
            observed.set(ex.pathParams());
            ex.respond(HttpStatus.OK);
        });
        if (!route.pathPattern().equals(reference.normalisedPattern())) {
            tally.disagree("pattern normalisation", pattern, "", reference.normalisedPattern(), route.pathPattern());
        }
        if (!route.paramNames().equals(reference.names())) {
            tally.disagree("parameter names", pattern, "", reference.names().toString(), route.paramNames().toString());
        }
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(route);

        for (int k = 0; k < PATHS_PER_PATTERN; k++) {
            String path = generator.path(pattern);
            tally.pairs.increment();

            // the route on the whole string, as given
            Map<String, String> expected = reference.parameters(path);
            boolean decision = route.matchesPath(path);
            if (decision != (expected != null)) {
                tally.disagree("route decision", pattern, path, describe(expected), String.valueOf(decision));
            }
            Map<String, String> extracted = route.extractPathParams(path);
            Map<String, String> expectedExtracted = expected == null ? Map.of() : expected;
            if (!extracted.equals(expectedExtracted)) {
                tally.disagree("route parameters", pattern, path, describe(expectedExtracted), describe(extracted));
            } else if (!List.copyOf(extracted.entrySet()).equals(List.copyOf(expectedExtracted.entrySet()))) {
                tally.disagree("route parameter order", pattern, path, describe(expectedExtracted), describe(extracted));
            }
            if (expected != null) {
                tally.matched.increment();
                if (!reference.names().isEmpty()) {
                    tally.matchedWithParameters.increment();
                }
                if (reference.names().size() > 1) {
                    tally.matchedWithSeveralParameters.increment();
                }
            }

            // the registry, end to end, on the request path with its query string cut off first
            Map<String, String> expectedServed = reference.parameters(ReferencePathPattern.withoutQuery(path));
            observed.set(null);
            ScriptedExchange exchange;
            try {
                exchange = ScriptedExchange.get(path);
            } catch (RuntimeException refused) {
                tally.shortLived.increment();
                continue;
            }
            registry.handle(exchange);
            HttpStatus status = exchange.statuses().isEmpty() ? null : exchange.statuses().get(0);
            HttpStatus expectedStatus = expectedServed != null ? HttpStatus.OK : HttpStatus.NOT_FOUND;
            if (status != expectedStatus) {
                tally.disagree("registry status", pattern, path, String.valueOf(expectedStatus), String.valueOf(status));
            } else if (expectedServed != null) {
                tally.matchedRegistry.increment();
                Map<String, String> seen = observed.get();
                if (seen == null || !seen.equals(expectedServed)) {
                    tally.disagree("registry parameters", pattern, path, describe(expectedServed), describe(seen));
                }
                var matched = registry.matchRoute(HttpMethod.GET, path);
                if (matched.isEmpty() || !matched.get().pathParams().equals(expectedServed)) {
                    tally.disagree("matchRoute parameters", pattern, path, describe(expectedServed),
                            matched.map(m -> describe(m.pathParams())).orElse("empty"));
                }
            } else if (registry.matchRoute(HttpMethod.GET, path).isPresent()) {
                tally.disagree("matchRoute decision", pattern, path, "no match", "match");
            }
        }
    }

    private static Tally shared;

    /** One run of all the pairs, shared by the tests that read its figures. */
    private static synchronized Tally run() {
        if (shared != null) {
            return shared;
        }
        Tally tally = new Tally();
        IntStream.range(0, CHUNKS).parallel().forEach(chunk -> {
            RouteGenerator generator = new RouteGenerator(0x5EEDL * 1_000_003L + chunk);
            for (int p = 0; p < PATTERNS_PER_CHUNK; p++) {
                compareOnePattern(generator, tally);
            }
        });
        System.out.println("differential: " + tally.pairs.sum() + " pairs compared, " + tally.matched.sum()
                + " matched the reference pattern (" + tally.matchedWithParameters.sum() + " of them with parameters, "
                + tally.matchedWithSeveralParameters.sum() + " with several), " + tally.matchedRegistry.sum()
                + " served through the registry, " + tally.shortLived.sum() + " refused by the exchange");
        if (!tally.disagreements.isEmpty()) {
            System.out.println("differential disagreements:" + tally.report());
        }
        shared = tally;
        return tally;
    }

    @Test
    @DisplayName("2,400,000 generated pairs, a large share of them matching, with parameters and with several")
    void theComparisonIsNotVacuous() {
        Tally tally = run();
        assertThat(tally.pairs.sum()).isEqualTo(PAIRS).isGreaterThanOrEqualTo(2_000_000L);
        assertThat(tally.matchedWithParameters.sum()).as("matching pairs with parameters").isGreaterThan(300_000L);
        assertThat(tally.matchedWithSeveralParameters.sum()).as("matching pairs with several parameters").isGreaterThan(100_000L);
        assertThat(tally.pairs.sum() - tally.matched.sum()).as("pairs that do not match").isGreaterThan(500_000L);
        assertThat(tally.matchedRegistry.sum()).as("pairs served through the registry").isGreaterThan(500_000L);
        assertThat(tally.shortLived.sum()).as("paths the exchange refused").isZero();
    }

    @Test
    @DisplayName("through the route and through the registry: the same decision and the same parameters as the reference pattern, for every pair")
    void routeAndRegistryAgreeWithTheReferencePattern() {
        assertThat(run().disagreements.keySet()).as("kinds of disagreement with the reference pattern, listed above").isEmpty();
    }

    @Test
    @DisplayName("through the route and through the registry: the same as the reference pattern for every pair whose pattern keeps a surrogate pair whole at each parameter")
    void agreementWherePatternsKeepPairsWhole() {
        assertThat(run().disagreements.keySet()).as("kinds of disagreement outside patterns that split a pair at a parameter")
                .allSatisfy(kind -> assertThat(kind).startsWith("[pattern splits a pair at a parameter]"));
    }

    // ---- what the constructor refuses -------------------------------------------------------------------------

    @Test
    @DisplayName("the route constructor refuses a null part and an abstaining requirement, and accepts every pattern text")
    void constructorRefusals() {
        var ok = Fixtures.ok();
        var permit = List.of(RouteRequirement.permitAll());
        assertThatThrownBy(() -> new ApiSurfaceRoute(null, "/a", "n", permit, ok)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurfaceRoute(HttpMethod.GET, null, "n", permit, ok)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurfaceRoute(HttpMethod.GET, "/a", null, permit, ok)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurfaceRoute(HttpMethod.GET, "/a", "n", permit, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurfaceRoute(HttpMethod.GET, "/a", "n", List.of(RouteRequirement.abstain()), ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ApiSurfaceRoute(HttpMethod.GET, "/a", "n", null, ok).requirements()).isEmpty();

        RouteGenerator generator = new RouteGenerator(77);
        List<String> texts = new ArrayList<>(List.of("", " ", "/", "//", "{", "}", "{}", "{a", "a}", "{a-b}", "{{a}}", "{a}{a}",
                "\\E", "\\Q{a}\\E", "\uD83D", "\uDE00", "/{a}\uD83D", "\uDE00{a}"));
        for (int i = 0; i < 5_000; i++) {
            texts.add(generator.pattern());
        }
        for (String text : texts) {
            ApiSurfaceRoute route = new ApiSurfaceRoute(HttpMethod.GET, text, "n", permit, ok);
            assertThat(route.pathPattern()).isEqualTo(ReferencePathPattern.normalise(text));
        }
        assertThat(new ApiSurfaceRoute(HttpMethod.GET, "/{a}{a}/{b}", "n", permit, ok).paramNames())
                .containsExactly("a", "a", "b");
        assertThat(new ApiSurfaceRoute(HttpMethod.GET, "/{a-b}/{}/{ a }", "n", permit, ok).paramNames())
                .as("a brace that does not enclose a name is literal text").isEmpty();
    }

    @Test
    @DisplayName("the registry refuses a second route of one method and one shape, whatever the parameter names")
    void duplicateShapeIsRefused() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/a/{x}-{y}"));
        assertThatThrownBy(() -> registry.registerRoute(Fixtures.open("/a/{p}-{q}")))
                .isInstanceOf(IllegalStateException.class);
        registry.registerRoute(Fixtures.route(HttpMethod.POST, "/a/{p}-{q}", List.of(RouteRequirement.permitAll()), Fixtures.ok()));
        registry.registerRoute(Fixtures.open("/a/{x}{y}"));
        assertThat(registry.routeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a null path is matched by no route, and a null method or path finds no route")
    void nullsMatchNothing() {
        ApiSurfaceRoute route = Fixtures.open("/a/{x}");
        assertThat(route.matchesPath(null)).isFalse();
        assertThat(route.extractPathParams(null)).isEmpty();
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(route);
        assertThat(registry.matchRoute(null, "/a/b")).isEmpty();
        assertThat(registry.matchRoute(HttpMethod.GET, null)).isEmpty();
    }

    // ---- named cases of the documented definition --------------------------------------------------------------

    private static Map<String, String> params(String pattern, String path) {
        ApiSurfaceRoute route = Fixtures.open(pattern);
        return route.matchesPath(path) ? route.extractPathParams(path) : null;
    }

    @Test
    @DisplayName("several parameters in one segment divide the path as the reference pattern does")
    void severalParametersInOneSegment() {
        assertThat(params("/files/{name}.{ext}", "/files/a.b.c")).containsEntry("name", "a.b").containsEntry("ext", "c");
        assertThat(params("/a/{x}-{y}", "/a/1-2-3")).containsEntry("x", "1-2").containsEntry("y", "3");
        assertThat(params("/{a}{b}", "/xyz")).containsEntry("a", "xy").containsEntry("b", "z");
        assertThat(params("/{a}{b}", "/x")).isNull();
        assertThat(params("/{a}/{a}", "/1/2")).hasSize(1).containsEntry("a", "2");
        assertThat(params("/{a}-{b}-{a}", "/1-2-3")).containsEntry("a", "3").containsEntry("b", "2");
    }

    @Test
    @DisplayName("a value never ends inside a surrogate pair, and a lone surrogate is a value of its own")
    void surrogatePairs() {
        String face = "😀";
        assertThat(params("/{a}{b}", "/" + face)).isNull();
        assertThat(params("/{a}{b}", "/" + face + face)).containsEntry("a", face).containsEntry("b", face);
        assertThat(params("/{a}\uDE00", "/😀")).as("the pair is one character").isNull();
        assertThat(params("/{a}", "/\uD83D")).containsEntry("a", "\uD83D");
        assertThat(params("/{a}", "/\uDE00")).containsEntry("a", "\uDE00");
    }

    @Test
    @DisplayName("a literal text does not begin or end inside a surrogate pair of the path")
    void literalTextAndSurrogatePairs() {
        // a pair in the path is one character; a literal text holding half of it matches nothing in it
        assertThat(params("/\uD83D{a}", "/😀")).as("literal ends with the high half of a pair").isNull();
        assertThat(params("/{a}\uDE00", "/x😀")).as("literal begins with the low half of a pair").isNull();
        assertThat(params("/x\uD83D{a}", "/x😀")).isNull();
    }

    @Test
    @DisplayName("parameter values keep encoded text, white space inside the path and any character but a slash")
    void valuesAreNotDecoded() {
        assertThat(params("/a/{x}", "/a/%2F")).containsEntry("x", "%2F");
        assertThat(params("/a/{x}", "/a/b c")).containsEntry("x", "b c");
        assertThat(params("/a/{x}", "/a/b\nc")).containsEntry("x", "b\nc");
        assertThat(params("/a/{x}", "/a/")).isNull();
        assertThat(params("/a/{x}", "/a/b/c")).isNull();
        assertThat(params("/a/{x}", " /a/b/ ")).containsEntry("x", "b");
        assertThat(params("/a/{x}", "a/b")).containsEntry("x", "b");
    }

    @Test
    @DisplayName("an exchange the route answers has the parameters of its request path, a query string excluded")
    void exchangeParametersExcludeTheQuery() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/o/{id}", (HttpExchange ex) -> {
            seen.set(ex.pathParams());
            ex.respond(HttpStatus.OK);
        }));
        ScriptedExchange exchange = ScriptedExchange.get("/o/42/?a=/b");
        registry.handle(exchange);
        assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        assertThat(seen.get()).isEqualTo(Map.of("id", "42"));
    }
}
