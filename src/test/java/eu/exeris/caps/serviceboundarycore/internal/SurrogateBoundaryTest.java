package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ReferencePathPattern;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a literal text and a parameter value meet inside a surrogate pair. A pair is one character of the path, so
 * no literal text and no value starts or ends between its halves; a lone surrogate is a character of its own. Every
 * case is decided by the route, by the registry and by the pattern on an explicit region, and each decision and
 * each parameter value is compared with the reference pattern.
 */
@DisplayName("Surrogate pairs at the boundaries of literal texts and parameter values")
class SurrogateBoundaryTest {

    private static final String H = "\uD83D";
    private static final String L = "\uDE00";
    private static final String P = H + L;

    /** One named case: the boundary it exercises, the pattern, the path and whether the path matches. */
    private record Case(String name, String pattern, String path, boolean matches) {
        @Override public String toString() {
            return name + ": pattern " + ReferencePathPattern.show(pattern) + " path " + ReferencePathPattern.show(path);
        }
    }

    private static Case c(String name, String pattern, String path, boolean matches) {
        return new Case(name, pattern, path, matches);
    }

    // ---- the comparison --------------------------------------------------------------------------------------

    private static String describe(Map<String, String> parameters) {
        return parameters == null ? "no match" : ReferencePathPattern.show(parameters.toString());
    }

    /**
     * Every disagreement with the reference pattern for one pattern and path: through the route on the whole path,
     * through the registry on the path cut at its query string, and through the compiled pattern on every region
     * {@code path[0, end)} when {@code everyEnd} is set.
     */
    private static List<String> disagreements(String pattern, String path, boolean everyEnd) {
        List<String> found = new ArrayList<>();
        ReferencePathPattern reference = new ReferencePathPattern(pattern);
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        ApiSurfaceRoute route = Fixtures.open(pattern, ex -> {
            observed.set(ex.pathParams());
            ex.respond(HttpStatus.OK);
        });

        Map<String, String> expected = reference.parameters(path);
        if (route.matchesPath(path) != (expected != null)) {
            found.add("route decision: reference " + describe(expected) + " actual " + route.matchesPath(path));
        }
        Map<String, String> extracted = route.extractPathParams(path);
        if (!extracted.equals(expected == null ? Map.of() : expected)) {
            found.add("route parameters: reference " + describe(expected) + " actual " + describe(extracted));
        }

        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(route);
        Map<String, String> expectedServed = reference.parameters(ReferencePathPattern.withoutQuery(path));
        ScriptedExchange exchange = ScriptedExchange.get(path);
        registry.handle(exchange);
        HttpStatus status = exchange.statuses().isEmpty() ? null : exchange.statuses().get(0);
        if (status != (expectedServed != null ? HttpStatus.OK : HttpStatus.NOT_FOUND)) {
            found.add("registry status: reference " + describe(expectedServed) + " actual " + status);
        } else if (expectedServed != null && !expectedServed.equals(observed.get())) {
            found.add("registry parameters: reference " + describe(expectedServed) + " actual " + describe(observed.get()));
        }

        if (everyEnd) {
            PathPattern compiled = new PathPattern(reference.normalisedPattern());
            for (int end = 0; end <= path.length(); end++) {
                String region = path.substring(0, end);
                Map<String, String> expectedRegion = reference.parameters(region);
                Map<String, String> actual = compiled.match(path, end);
                if (!java.util.Objects.equals(expectedRegion, actual) || compiled.matches(path, end) != (expectedRegion != null)) {
                    found.add("region [0," + end + "): reference " + describe(expectedRegion) + " actual " + describe(actual)
                            + " matches=" + compiled.matches(path, end));
                }
            }
        }
        return found;
    }

    private static DynamicTest dynamic(Case c) {
        return DynamicTest.dynamicTest(c.toString(), () -> {
            ReferencePathPattern reference = new ReferencePathPattern(c.pattern());
            String served = ReferencePathPattern.withoutQuery(c.path());
            assertThat(reference.matches(served)).as("the reference pattern decides as the case states").isEqualTo(c.matches());
            assertThat(new ApiSurfaceRoute(eu.exeris.kernel.spi.http.HttpMethod.GET, c.pattern(), "n", List.of(), Fixtures.ok())
                    .matchesPath(served)).as("the route decides as the case states").isEqualTo(c.matches());
            assertThat(disagreements(c.pattern(), c.path(), true)).as("disagreements with the reference pattern").isEmpty();
        });
    }

    // ---- named cases, one group for each kind of boundary -----------------------------------------------------

    @TestFactory
    @DisplayName("the start of the pattern meets the first value")
    Stream<DynamicTest> startToValue() {
        return Stream.of(
                c("a pair is the whole value", "/{a}", "/" + P, true),
                c("a lone low surrogate starts the value", "/{a}", "/" + L + "x", true),
                c("a lone high surrogate starts the value", "/{a}", "/" + H + "x", true),
                c("a path without a leading slash and a pair as value", "/{a}", P, true),
                c("the first literal ends with a high surrogate and the path has a pair there", "/" + H + "{a}", "/" + P, false),
                c("the first literal ends with a high surrogate and the path has a lone high one", "/" + H + "{a}", "/" + H + "x", true),
                c("the first literal ends with a low surrogate and the path has a pair there", "/" + L + "{a}", "/" + P, false),
                c("the first literal ends with a low surrogate and the path has a lone low one", "/" + L + "{a}", "/" + L + "x", true),
                c("white space before a path that starts with a pair", "/{a}", " \t/" + P + "x", true)).map(SurrogateBoundaryTest::dynamic);
    }

    @TestFactory
    @DisplayName("a literal text meets the value that follows it")
    Stream<DynamicTest> literalToValue() {
        return Stream.of(
                c("the literal ends with a high surrogate and the path has a pair", "/x" + H + "{a}", "/x" + P, false),
                c("the literal ends with a high surrogate and the path has a lone high surrogate", "/x" + H + "{a}", "/x" + H + "y", true),
                c("the literal ends with a high surrogate and nothing follows", "/x" + H + "{a}", "/x" + H, false),
                c("the literal ends with a pair", "/x" + P + "{a}", "/x" + P + "y", true),
                c("the literal ends with a pair and the path has only its high half", "/x" + P + "{a}", "/x" + H + "y", false),
                c("the literal ends with a low surrogate and the path has a pair", "/x" + L + "{a}", "/x" + H + L + "y", false),
                c("the literal ends with a low surrogate and the path has a lone low surrogate", "/x" + L + "{a}", "/x" + L + "y", true),
                c("a pair inside a literal", "/a" + P + "b{x}", "/a" + P + "bz", true),
                c("a pair inside a literal and a different pair in the path", "/a" + P + "b{x}", "/a😁bz", false),
                c("a pair inside a literal that the path splits with another character", "/a" + P + "b{x}", "/a" + H + "z" + L + "bz", false))
                .map(SurrogateBoundaryTest::dynamic);
    }

    @TestFactory
    @DisplayName("a value meets the literal text that follows it")
    Stream<DynamicTest> valueToLiteral() {
        return Stream.of(
                c("the literal starts with a low surrogate and the path has a pair", "/{a}" + L + "b", "/" + H + L + "b", false),
                c("the literal starts with a low surrogate and the path has a lone low surrogate", "/{a}" + L + "b", "/x" + L + "b", true),
                c("the literal is a high surrogate at the end and the path has a lone high surrogate", "/{a}" + H, "/x" + H, true),
                c("the literal is a high surrogate at the end and the path has a pair", "/{a}" + H, "/x" + P, false),
                c("the literal starts with a high surrogate and the path has a lone high surrogate", "/{a}" + H + "b", "/x" + H + "b", true),
                c("the literal is a pair", "/{a}" + P, "/x" + P, true),
                c("the literal is a pair between two values", "/{a}" + P + "{b}", "/x" + P + "y", true),
                c("the literal is a high surrogate between two values and the path has a pair", "/{a}" + H + "{b}", "/x" + P, false),
                c("the literal is a high surrogate between two values and the path has a lone high surrogate", "/{a}" + H + "{b}", "/x" + H + "y", true),
                c("the literal is a low surrogate between two values and the path has a pair", "/{a}" + L + "{b}", "/x" + P, false),
                c("the literal is a low surrogate between two values and the path has a lone low surrogate", "/{a}" + L + "{b}", "/x" + L + "y", true),
                c("the value is a pair before a literal", "/{a}x", "/" + P + "x", true),
                c("the value ends with a lone high surrogate before a literal that starts with a low one",
                        "/{a}" + L + "x", "/y" + H + L + "x", false)).map(SurrogateBoundaryTest::dynamic);
    }

    @TestFactory
    @DisplayName("two adjacent values meet, and a pair may straddle them")
    Stream<DynamicTest> valueToValue() {
        return Stream.of(
                c("a pair cannot be divided between two values", "/{a}{b}", "/" + P, false),
                c("two pairs are one value each", "/{a}{b}", "/" + P + P, true),
                c("a lone high surrogate and a character", "/{a}{b}", "/" + H + "x", true),
                c("a character and a lone low surrogate", "/{a}{b}", "/x" + L, true),
                c("a lone low surrogate and a lone high surrogate, in this order", "/{a}{b}", "/" + L + H, true),
                c("a high surrogate and a pair: the pair is not divided", "/{a}{b}", "/" + H + P, true),
                c("two high surrogates and a low one: the pair is the second value", "/{a}{b}", "/" + H + H + L, true),
                c("a pair and a character", "/{a}{b}", "/" + P + "x", true),
                c("a character and a pair", "/{a}{b}", "/x" + P, true),
                c("three values and a pair that two of them cannot share", "/{a}{b}{c}", "/" + P + L + H, true),
                c("three values and only a pair", "/{a}{b}{c}", "/" + P, false),
                c("three values and a pair and a character", "/{a}{b}{c}", "/" + P + "x", false),
                c("the values are in different segments", "/{a}/{b}", "/" + H + "/" + L, true),
                c("a pair is the whole value of each of two segments", "/{a}/{b}", "/" + P + "/" + P, true)).map(SurrogateBoundaryTest::dynamic);
    }

    @TestFactory
    @DisplayName("the last value meets the end of the path")
    Stream<DynamicTest> valueToEnd() {
        return Stream.of(
                c("the value ends with a lone high surrogate", "/{a}", "/x" + H, true),
                c("the value ends with a lone low surrogate", "/{a}", "/x" + L, true),
                c("the value ends with a pair", "/{a}", "/x" + P, true),
                c("the value ends with a lone high surrogate before a dropped trailing slash", "/{a}", "/x" + H + "/", true),
                c("the value ends with a lone high surrogate before trailing white space", "/{a}", "/x" + H + " \t", true),
                c("the value ends with a lone high surrogate before a query string that starts with a low surrogate", "/{a}",
                        "/x" + H + "?" + L, true),
                c("the value ends with a lone high surrogate before a query string that holds a pair", "/{a}", "/x" + H + "?" + P, true),
                c("a literal high surrogate ends the pattern and the path has a lone high one before a query string",
                        "/{a}" + H, "/x" + H + "?" + L, true),
                c("a literal high surrogate ends the pattern and the path has a pair", "/{a}" + H, "/x" + P, false),
                c("a literal low surrogate ends the pattern and the path has a lone low one", "/{a}" + L, "/x" + L, true),
                c("a literal low surrogate ends the pattern and the path has a pair", "/{a}" + L, "/x" + P, false),
                c("two values and a lone high surrogate last", "/{a}{b}", "/x" + H, true)).map(SurrogateBoundaryTest::dynamic);
    }

    @TestFactory
    @DisplayName("a pattern or a path made only of surrogates, in every arrangement of two")
    Stream<DynamicTest> onlySurrogates() {
        String[] halves = {H, L, P, L + H, "", "x"};
        List<Case> cases = new ArrayList<>();
        for (String first : halves) {
            for (String second : halves) {
                cases.add(c("literal then value", "/" + first + "{a}" + second, "/" + first + second + "y" + second, true));
                cases.add(c("value then literal", "/{a}" + first + second, "/y" + first + second, true));
            }
        }
        // the stated expectation is the reference pattern's, so only agreement is asserted for the arrangements
        return cases.stream().map(k -> DynamicTest.dynamicTest(k.toString(),
                () -> assertThat(disagreements(k.pattern(), k.path(), true)).isEmpty()));
    }

    // ---- a region that stops before a low surrogate -----------------------------------------------------------

    @Test
    @DisplayName("a region that ends with a high surrogate is complete there, whatever character follows it in the string")
    void aRegionEndsAtABoundaryBeforeALowSurrogate() {
        PathPattern value = new PathPattern("/{a}");
        String path = "/x" + H + L + "tail";
        assertThat(value.matches(path, 3)).isTrue();
        assertThat(value.match(path, 3)).isEqualTo(Map.of("a", "x" + H));
        assertThat(value.match(path, 4)).as("the region holds the pair").isEqualTo(Map.of("a", "x" + P));

        PathPattern literal = new PathPattern("/{a}" + H);
        assertThat(literal.match(path, 3)).as("the literal high surrogate is the end of the region").isEqualTo(Map.of("a", "x"));
        assertThat(literal.matches(path, 4)).as("with the pair in the region the literal splits it").isFalse();

        PathPattern adjacent = new PathPattern("/{a}{b}");
        assertThat(adjacent.match(path, 3)).isEqualTo(Map.of("a", "x", "b", H));

        // trailing white space after the region, then a low surrogate: the region is trimmed before the boundary is taken
        String spaced = "/x" + H + " " + L;
        assertThat(value.match(spaced, 3)).isEqualTo(Map.of("a", "x" + H));
        assertThat(value.match(spaced, 4)).isEqualTo(Map.of("a", "x" + H));
        assertThat(value.match(spaced, 5)).isEqualTo(Map.of("a", "x" + H + " " + L));

        for (String text : new String[] {path, spaced, "/" + H + L + H + L, "/" + H + H + L + L}) {
            assertThat(disagreements("/{a}", text, true)).as(text).isEmpty();
            assertThat(disagreements("/{a}" + H, text, true)).as(text).isEmpty();
            assertThat(disagreements("/" + H + "{a}", text, true)).as(text).isEmpty();
            assertThat(disagreements("/{a}{b}", text, true)).as(text).isEmpty();
        }
    }

    // ---- a lone surrogate at every position of a pattern and of a path -------------------------------------------

    private static final String[] PATTERN_SKELETONS = {"/{a}", "/{a}{b}", "/x{a}y", "/{a}-{b}", "/{a}/{b}", "/{a}x{b}", "/p/{a}-"};
    private static final String[] PATH_SKELETONS = {"/", "/x", "/xy", "/x-y", "/x/y", "/ab-cd", "/xy-", "/p/x-", "/x-/y", "/xxy"};
    private static final String[] FRAGMENTS = {H, L, P, L + H};
    private static final String[] HALVES = {H, L};

    private static List<String> insertions(String skeleton, String[] fragments) {
        List<String> out = new ArrayList<>();
        out.add(skeleton);
        for (int i = 0; i <= skeleton.length(); i++) {
            for (String fragment : fragments) {
                out.add(skeleton.substring(0, i) + fragment + skeleton.substring(i));
            }
        }
        return out;
    }

    @Test
    @DisplayName("a surrogate or a pair inserted at every position of every pattern and path agrees with the reference pattern, on every region")
    void everyPositionOfEveryPatternAndPath() {
        List<String> patterns = new ArrayList<>();
        for (String skeleton : PATTERN_SKELETONS) {
            patterns.addAll(insertions(skeleton, FRAGMENTS));
            for (String once : insertions(skeleton, HALVES)) {
                // a second lone half, so that a literal text can hold both halves of a pair apart from a parameter
                patterns.addAll(insertions(once, HALVES));
            }
        }
        List<String> paths = new ArrayList<>();
        List<String> pathsForEveryRegion = new ArrayList<>();
        for (String skeleton : PATH_SKELETONS) {
            pathsForEveryRegion.addAll(insertions(skeleton, FRAGMENTS));
            paths.addAll(pathsForEveryRegion);
            for (String once : insertions(skeleton, HALVES)) {
                paths.addAll(insertions(once, HALVES));
            }
        }
        java.util.Set<String> everyRegion = new java.util.HashSet<>(pathsForEveryRegion);
        long pairs = 0;
        long matching = 0;
        long halfAtParameter = 0;
        List<String> failures = new ArrayList<>();
        for (String pattern : patterns) {
            ReferencePathPattern reference = new ReferencePathPattern(pattern);
            boolean atParameter = java.util.regex.Pattern.compile("[\\uD800-\\uDBFF]\\{[a-zA-Z0-9_]+}|\\{[a-zA-Z0-9_]+}[\\uDC00-\\uDFFF]")
                    .matcher(reference.normalisedPattern()).find();
            for (String path : new java.util.LinkedHashSet<>(paths)) {
                pairs++;
                if (reference.matches(path)) {
                    matching++;
                }
                if (atParameter) {
                    halfAtParameter++;
                }
                if (failures.size() < 10) {
                    List<String> found = disagreements(pattern, path, everyRegion.contains(path) && path.length() <= 8);
                    for (String f : found) {
                        failures.add("pattern " + ReferencePathPattern.show(pattern) + " path " + ReferencePathPattern.show(path) + " " + f);
                    }
                } else {
                    break;
                }
            }
        }
        System.out.println("surrogate boundaries: " + patterns.size() + " patterns, " + new java.util.LinkedHashSet<>(paths).size()
                + " paths, " + pairs + " pairs compared, " + matching + " of them matching the reference pattern, " + halfAtParameter
                + " with half a pair next to a parameter in the pattern");
        assertThat(failures).as("disagreements with the reference pattern").isEmpty();
        assertThat(pairs).isGreaterThan(100_000L);
        assertThat(matching).as("pairs that match").isGreaterThan(5_000L);
        assertThat(halfAtParameter).as("pairs with half a pair next to a parameter").isGreaterThan(20_000L);
    }
}
