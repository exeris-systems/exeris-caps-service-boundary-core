package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Documentation as contract: how a request is matched to a route")
class RouteMatchingDocumentationTest {

    private static final String REGISTRY = "src/main/java/eu/exeris/caps/serviceboundarycore/internal/DefaultApiSurfaceRegistry.java";
    private static final String PATH_PATTERN = "src/main/java/eu/exeris/caps/serviceboundarycore/internal/PathPattern.java";

    /** A file's text with comment markers and tags' braces kept, and the whitespace collapsed. */
    private static String prose(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("(?m)^\\s*(/\\*\\*|\\*/|\\*|//)[ \\t]?", " ").replaceAll("\\s+", " ");
    }

    private final List<String> served = new ArrayList<>();

    private DefaultApiSurfaceRegistry registryOf(ApiSurfaceRoute... routes) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        for (ApiSurfaceRoute route : routes) {
            registry.registerRoute(route);
        }
        return registry;
    }

    private ApiSurfaceRoute named(HttpMethod method, String pattern, String name) {
        return Fixtures.route(method, pattern, List.of(RouteRequirement.permitAll()), ex -> {
            served.add(name);
            ex.respond(HttpStatus.OK);
        });
    }

    // ---- README: Route matching ------------------------------------------------------------------------------------

    @Test
    @DisplayName("README: a route without parameters is exact and wins over a route with parameters, whichever was registered first, among the routes of the request's method; among routes with parameters the first registered that matches wins")
    void readmeSelectionSentence() throws IOException {
        assertThat(prose("README.md")).contains("**Route matching.** A route without path parameters is exact and wins over a route with "
                + "parameters, whichever was registered first, among the routes of the request's method; among routes with parameters "
                + "the first registered that matches wins.");
        for (boolean literalFirst : new boolean[] {true, false}) {
            served.clear();
            ApiSurfaceRoute literal = named(HttpMethod.GET, "/a/b", "literal");
            ApiSurfaceRoute wide = named(HttpMethod.GET, "/a/{x}", "wide");
            ApiSurfaceRoute narrow = named(HttpMethod.GET, "/{y}/b", "narrow");
            DefaultApiSurfaceRegistry registry = literalFirst ? registryOf(literal, wide, narrow) : registryOf(wide, narrow, literal);
            registry.handle(ScriptedExchange.get("/a/b"));
            assertThat(served).containsExactly("literal");
            served.clear();
            registry.handle(ScriptedExchange.get("/a/c"));
            registry.handle(ScriptedExchange.get("/q/b"));
            assertThat(served).containsExactly("wide", "narrow");
        }
        served.clear();
        registryOf(named(HttpMethod.GET, "/{y}/b", "narrow"), named(HttpMethod.GET, "/a/{x}", "wide"))
                .handle(ScriptedExchange.get("/a/b"));
        assertThat(served).as("the first registered of two that match").containsExactly("narrow");

        // precedence holds among the routes of the request's method: a literal route of another method wins nothing,
        // and a parameterised route of another method registered first is not the first that matches
        for (boolean otherFirst : new boolean[] {true, false}) {
            served.clear();
            ApiSurfaceRoute postLiteral = named(HttpMethod.POST, "/a/b", "post literal");
            ApiSurfaceRoute postWide = named(HttpMethod.POST, "/{y}/b", "post narrow");
            ApiSurfaceRoute getWide = named(HttpMethod.GET, "/a/{x}", "get wide");
            DefaultApiSurfaceRegistry mixed = otherFirst ? registryOf(postLiteral, postWide, getWide) : registryOf(getWide, postWide, postLiteral);
            mixed.handle(ScriptedExchange.get("/a/b"));
            assertThat(served).as("a GET request is served by the GET route, otherFirst=%s", otherFirst).containsExactly("get wide");
            served.clear();
            mixed.handle(ScriptedExchange.of(HttpMethod.POST, "/a/b", List.of()));
            assertThat(served).as("a POST request is served by the literal POST route, otherFirst=%s", otherFirst).containsExactly("post literal");
        }
        served.clear();
        DefaultApiSurfaceRegistry sameMethod = registryOf(named(HttpMethod.GET, "/a/b", "get literal"),
                named(HttpMethod.POST, "/a/{x}", "post wide"));
        ScriptedExchange post = ScriptedExchange.of(HttpMethod.POST, "/a/b", List.of());
        sameMethod.handle(post);
        assertThat(served).as("a literal GET route does not take a POST request").containsExactly("post wide");
        assertThat(post.statuses()).containsExactly(HttpStatus.OK);
    }

    @Test
    @DisplayName("README: the literal route that wins is the one of the request's method; a literal route of another method does not take the request")
    void aLiteralRouteOfAnotherMethodDoesNotWin() {
        DefaultApiSurfaceRegistry registry = registryOf(named(HttpMethod.POST, "/a/b", "post literal"),
                named(HttpMethod.GET, "/a/{x}", "get parameterised"));
        registry.handle(ScriptedExchange.get("/a/b"));
        assertThat(served).containsExactly("get parameterised");
    }

    @Test
    @DisplayName("README: a {name} parameter stands for one or more characters other than a slash, without decoding, and need not be a whole segment")
    void readmeParameterSentence() throws IOException {
        assertThat(prose("README.md")).contains("A `{name}` parameter stands for one or more characters other than `/`, without decoding, "
                + "and need not be a whole segment: `/files/{name}.{ext}` has two parameters in one segment.");
        ApiSurfaceRoute whole = Fixtures.open("/a/{x}");
        assertThat(whole.extractPathParams("/a/%41%2F")).isEqualTo(Map.of("x", "%41%2F"));
        assertThat(whole.matchesPath("/a/")).as("one or more characters").isFalse();
        assertThat(whole.matchesPath("/a/b/c")).as("no slash").isFalse();
        ApiSurfaceRoute part = Fixtures.open("/files/{name}.{ext}");
        assertThat(part.paramNames()).as("two parameters in one segment").containsExactly("name", "ext");
        assertThat(part.extractPathParams("/files/report.pdf")).as("a parameter inside a segment").isEqualTo(Map.of("name", "report", "ext", "pdf"));
        assertThat(part.matchesPath("/files/report")).as("the literal dot between the parameters is required").isFalse();
        assertThat(part.matchesPath("/files/.pdf")).as("each parameter takes one or more characters").isFalse();
        assertThat(part.matchesPath("/files/a.b/c")).as("no value contains a slash").isFalse();
        ApiSurfaceRoute inside = Fixtures.open("/v{n}/x{a}y");
        assertThat(inside.extractPathParams("/v1/xzy")).as("a literal text on both sides of a parameter in a segment")
                .isEqualTo(Map.of("n", "1", "a", "z"));
        assertThat(inside.extractPathParams("/v%41/x%2Fy")).as("without decoding").isEqualTo(Map.of("n", "%41", "a", "%2F"));
    }

    @Test
    @DisplayName("README: a request for a route without parameters does not test any route with parameters")
    void aLiteralRequestNeverTestsAParameterisedRoute() throws Exception {
        assertThat(prose("README.md")).contains("A request for a route without parameters does not test any route with parameters.");
        // a route with parameters whose test would take minutes on this path, registered first
        String path = "/" + "a".repeat(100);
        DefaultApiSurfaceRegistry registry = registryOf(
                named(HttpMethod.GET, "/{a}{b}{c}{d}{e}{f}x", "pathological"),
                named(HttpMethod.GET, path, "literal"));
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> request = executor.submit(() -> registry.handle(ScriptedExchange.get(path)));
            request.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(served).containsExactly("literal");
    }

    @Test
    @DisplayName("README: a request is matched, and answered 404 or 405 when it is not, against one table of routes read once")
    void readmeOneTableSentence() throws Exception {
        assertThat(prose("README.md")).contains("A request is matched, and answered 404 or 405 when it is not, against one table of routes read once.");
        Field routes = DefaultApiSurfaceRegistry.class.getDeclaredField("routes");
        assertThat(Modifier.isVolatile(routes.getModifiers())).as("the table is one volatile field").isTrue();
        assertThat(Modifier.isFinal(routes.getModifiers())).isFalse();
        // a registration made by a handler is not seen by the request that is running; the next request sees it
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/trigger", ex -> {
            registry.registerRoute(Fixtures.open("/later/{id}"));
            ex.respond(HttpStatus.OK);
        }));
        ScriptedExchange before = ScriptedExchange.get("/later/1");
        registry.handle(before);
        registry.handle(ScriptedExchange.get("/trigger"));
        ScriptedExchange after = ScriptedExchange.get("/later/1");
        registry.handle(after);
        assertThat(before.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        assertThat(after.statuses()).containsExactly(HttpStatus.OK);
    }

    @Test
    @DisplayName("README: a route with parameters that matches allocates one String per distinct name, an immutable map and the match object; matching uses no regular expression, registering a route and constructing its pattern do")
    void readmeAllocationSentence() throws IOException {
        assertThat(prose("README.md")).contains("Matching allocates nothing for a route that does not match; a route with parameters that "
                + "matches allocates one `String` per distinct parameter name, the immutable map that holds them (more for more than four "
                + "distinct names) and the match object. Matching a request uses no regular expression; registering a route and "
                + "constructing its pattern do.");
        for (String pattern : new String[] {"/{a}", "/{a}/{b}", "/{a}/{b}/{c}/{d}", "/{a}/{b}/{c}/{d}/{e}", "/{a}/{a}"}) {
            PathPattern compiled = new PathPattern(pattern);
            String path = pattern.replaceAll("\\{[a-z]\\}", "v");
            Map<String, String> params = compiled.match(path, path.length());
            assertThat(params).as(pattern).isNotNull();
            assertThat(new RouteMatch(Fixtures.open(pattern), params).pathParams()).as("%s: kept without a copy", pattern).isSameAs(params);
            assertThat(params.getClass().getName()).startsWith("java.util.ImmutableCollections$");
        }
        assertThat(new PathPattern("/a").match("/a", 2)).as("without parameters the shared empty map").isSameAs(Map.of());
        assertThat(new PathPattern("/a/{x}").match("/b/c", 4)).isNull();
        // a request that the registry answers from a parameterised route gets a match object that holds that same map
        DefaultApiSurfaceRegistry registry = registryOf(Fixtures.open("/a/{x}/{y}"));
        RouteMatch match = registry.matchRoute(HttpMethod.GET, "/a/1/2").orElseThrow();
        assertThat(match.pathParams()).isEqualTo(Map.of("x", "1", "y", "2"));
        assertThat(registry.matchRoute(HttpMethod.GET, "/a/1/2").orElseThrow()).as("a new match object for each hit").isNotSameAs(match);
        // more than four distinct names: still an immutable map holding every value
        PathPattern many = new PathPattern("/{a}/{b}/{c}/{d}/{e}/{f}");
        Map<String, String> six = many.match("/1/2/3/4/5/6", 12);
        assertThat(six).containsOnlyKeys("a", "b", "c", "d", "e", "f").containsEntry("f", "6");
        assertThat(six.getClass().getName()).startsWith("java.util.ImmutableCollections$");
    }

    // ---- no regular expression on the request path --------------------------------------------------------------------

    private static final Set<String> REGEX_ENTRY_POINTS_OF_STRING = Set.of("matches", "replaceAll", "replaceFirst", "split", "splitWithDelimiters");

    private static List<String> regexUses(Class<?> type, Set<String> methods) throws IOException {
        List<String> uses = new ArrayList<>();
        try (InputStream bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            ClassModel model = ClassFile.of().parse(bytes.readAllBytes());
            Set<String> found = new java.util.HashSet<>();
            for (MethodModel method : model.methods()) {
                String name = method.methodName().stringValue();
                if (!methods.contains(name)) {
                    continue;
                }
                found.add(name);
                method.code().ifPresent(code -> code.forEach(element -> {
                    if (element instanceof InvokeInstruction call) {
                        String owner = call.owner().asInternalName();
                        String callee = call.name().stringValue();
                        if (owner.startsWith("java/util/regex/")
                                || (owner.equals("java/lang/String") && REGEX_ENTRY_POINTS_OF_STRING.contains(callee))) {
                            uses.add(type.getSimpleName() + "." + name + " calls " + owner + "." + callee);
                        }
                    }
                }));
            }
            assertThat(found).as("the methods named exist in %s", type.getSimpleName()).isEqualTo(methods);
        }
        return uses;
    }

    @Test
    @DisplayName("README and ROADMAP: no method that matches a request path, in the pattern, the route or the registry, calls java.util.regex; registering a route and constructing its pattern do")
    void theRequestPathUsesNoRegularExpression() throws IOException {
        assertThat(regexUses(PathPattern.class, Set.of("matches", "match", "region", "regionStart", "regionLimit", "startsWithSlash",
                "startPosition", "matchesRegion", "matchParameters", "parameterEnd", "valueLimit", "splitsPair"))).isEmpty();
        assertThat(regexUses(ApiSurfaceRoute.class, Set.of("matchesPath", "extractPathParams", "normalizePath", "matchesMethod"))).isEmpty();
        assertThat(regexUses(DefaultApiSurfaceRegistry.class, Set.of("findMatch", "servesPath", "matchesLiteral", "handle", "matchRoute",
                "routes", "routeCount"))).isEmpty();
        assertThat(regexUses(PathPattern.class, Set.of("<init>")))
                .as("the check can see a call to the regular expression classes: the constructor makes some")
                .isNotEmpty();
        // registration: the duplicate-shape check erases parameter names with a regular expression, and registerRoute calls it
        assertThat(regexUses(DefaultApiSurfaceRegistry.class, Set.of("shape")))
                .as("the check can see the regular expression of registration")
                .anySatisfy(use -> assertThat(use).contains("java/util/regex/"));
        assertThat(calls(DefaultApiSurfaceRegistry.class, "registerRoute")).contains("shape");
        assertThat(calls(ApiSurfaceRoute.class, "<init>")).as("a route constructs its pattern").contains("PathPattern.<init>");
    }

    /** The methods a method calls, as {@code Owner.name} for another class and {@code name} for its own. */
    private static Set<String> calls(Class<?> type, String method) throws IOException {
        Set<String> called = new java.util.HashSet<>();
        try (InputStream bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            ClassModel model = ClassFile.of().parse(bytes.readAllBytes());
            for (MethodModel candidate : model.methods()) {
                if (candidate.methodName().stringValue().equals(method)) {
                    candidate.code().ifPresent(code -> code.forEach(element -> {
                        if (element instanceof InvokeInstruction call) {
                            String owner = call.owner().asInternalName();
                            String simple = owner.substring(owner.lastIndexOf('/') + 1);
                            called.add(owner.equals(type.getName().replace('.', '/')) ? call.name().stringValue()
                                    : simple + "." + call.name().stringValue());
                        }
                    }));
                }
            }
        }
        return called;
    }

    // ---- the class Javadoc: the snapshot argument --------------------------------------------------------------------

    @Test
    @DisplayName("Javadoc: the routes are one immutable table read once per request before the phase, used for the match and for the 404 or 405")
    void snapshotArgumentSentences() throws Exception {
        assertThat(prose(REGISTRY))
                .contains("<b>Invariant: the routes are read once per request, before the phase.</b> The routes a request can serve are "
                        + "one immutable table, held in the volatile field {@code routes}: every route in registration order, the routes "
                        + "without parameters, and the routes with parameters in registration order.")
                .contains("{@code handle} reads the field once, before it reads {@code phase}, and uses that table for the match and, "
                        + "when nothing matched, for the 404 or 405 decision.")
                .contains("and be answered 405 with an {@code Allow} header naming its own method. With one table that cannot happen: "
                        + "if the table lacks the route the answer is 404, and if it has the route the request is matched.")
                .contains("a removal in {@code TEARDOWN} writes {@code routes} after the {@code TEARDOWN} write, so a request whose table "
                        + "comes from such a removal reads {@code TEARDOWN} next and answers 503, and a request that does not read "
                        + "{@code TEARDOWN} holds a table that predates every removal made during teardown.");
        // exercised by RouteSnapshotRaceTest: a request racing a registration is never answered 405 with its own method,
        // and one racing a removal made after teardown began is answered 200 or 503
        Field table = DefaultApiSurfaceRegistry.class.getDeclaredField("routes");
        assertThat(table.getType().getSimpleName()).isEqualTo("RouteTable");
        for (java.lang.reflect.RecordComponent component : table.getType().getRecordComponents()) {
            assertThat(component.getName()).isIn("all", "literals", "parameterised");
        }
    }

    // ---- PathPattern Javadoc ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Javadoc: a stray brace is literal text, surrounding characters up to a space are trimmed, and one trailing slash is dropped")
    void pathPatternNormalisationSentences() throws IOException {
        assertThat(prose(PATH_PATTERN))
                .contains("A parameter is written {@code {name}} with a name of letters, digits and underscores; every other character, "
                        + "a stray brace included, is literal text.")
                .contains("surrounding white space (every character up to a space) is trimmed, a leading slash is added when missing and "
                        + "one trailing slash is dropped from a result longer than one character.");
        ApiSurfaceRoute route = Fixtures.open("/a/{x}");
        assertThat(route.extractPathParams("\u0001\u0000 /a/b/ \t\n")).isEqualTo(Map.of("x", "b"));
        assertThat(route.matchesPath(" /a/b")).as("a no-break space is not trimmed").isFalse();
        assertThat(route.matchesPath("/a/b//")).as("only one trailing slash is dropped").isFalse();
        assertThat(Fixtures.open("/a/{x").matchesPath("/a/{x")).as("a stray brace is literal").isTrue();
        assertThat(Fixtures.open("/a/{x}").paramNames()).containsExactly("x");
        assertThat(Fixtures.open("/a/{}").paramNames()).isEmpty();
        assertThat(Fixtures.open("/a/{x-y}").paramNames()).isEmpty();
        assertThat(Fixtures.open("/a/{x}{y").paramNames()).containsExactly("x");
        AtomicReference<Map<String, String>> repeated = new AtomicReference<>();
        repeated.set(Fixtures.open("/{a}-{a}").extractPathParams("/1-2"));
        assertThat(repeated.get()).as("the later value is kept").isEqualTo(Map.of("a", "2"));
    }

    @Test
    @DisplayName("Javadoc: a parameter takes the longest value for which the rest matches, and every boundary of a literal text and of a value is on a code point boundary")
    void pathPatternGreedySentences() throws IOException {
        assertThat(prose(PATH_PATTERN))
                .contains("giving each parameter in turn the longest value for which the rest matches.")
                .contains("whose unit is the code point.")
                .contains("Every place where a literal text meets a parameter value (the start of the pattern, a literal text "
                        + "before and after a value, two adjacent values) is therefore a code point boundary of the path: it lies "
                        + "between two code points, never between the two halves of a surrogate pair.")
                .contains("A literal text is compared to the path char by char between two such boundaries, so a lone surrogate in a "
                        + "literal text matches only a lone surrogate in the path, and a value neither starts nor ends inside a pair. "
                        + "The end of the path is a boundary.");
        assertThat(Fixtures.open("/{a}-{b}").extractPathParams("/1-2-3")).isEqualTo(Map.of("a", "1-2", "b", "3"));
        assertThat(Fixtures.open("/{a}{b}").extractPathParams("/abc")).isEqualTo(Map.of("a", "ab", "b", "c"));
        assertThat(Fixtures.open("/{a}{b}").matchesPath("/\uD83D\uDE00")).as("a pair is not divided between two values").isFalse();
        assertThat(Fixtures.open("/{a}\uDE00").matchesPath("/\uD83D\uDE00")).as("a literal low surrogate is not the second half of a pair").isFalse();
        assertThat(Fixtures.open("/{a}").extractPathParams("/\uD83D")).as("a lone surrogate in the path is a value").isEqualTo(Map.of("a", "\uD83D"));
        assertThat(Fixtures.open("/{a}\uD83D").matchesPath("/x\uD83D")).as("a lone surrogate literal matches a lone surrogate").isTrue();
        assertThat(new PathPattern("/{a}\uD83D").matches("/x\uD83D\uDE00", 3)).as("the end of the path is a boundary").isTrue();
    }

    @Test
    @DisplayName("Javadoc and README: the time grows polynomially with the segment that parameters share, and linearly for one parameter per segment")
    void costSentences() throws IOException {
        assertThat(prose(PATH_PATTERN))
                .contains("The time to match a request is polynomial in the length of the path segment that the parameters share. A "
                        + "route with one parameter in each segment is linear in the path.")
                .contains("tries the divisions of that text between them: the time grows with a degree up to the number of such "
                        + "parameters. A route author keeps it small by writing one parameter per segment.");
        assertThat(prose("README.md"))
                .contains("The time to match a request against a route with several parameters grows polynomially with the length of "
                        + "the path segment they share, with a degree up to the number of parameters that can share text: adjacent "
                        + "parameters, or parameters separated by a literal text that may also occur inside a value. A route with one "
                        + "parameter per segment is linear in the path, so a route author writes one parameter per segment.");

        // one parameter per segment: the time for a path 8 times as long is far below the 64 times of a quadratic cost
        record Case(String pattern, java.util.function.IntFunction<String> path, boolean matches) { }
        List<Case> cases = List.of(
                new Case("/{a}/{b}/{c}x", n -> "/" + "a".repeat(n) + "/" + "a".repeat(n) + "/" + "a".repeat(n), false),
                new Case("/files/{name}/v/{id}", n -> "/files/" + "n".repeat(n) + "/v/" + "i".repeat(n), true),
                new Case("/{a}x/{b}y/{c}", n -> "/" + "a".repeat(n) + "x/" + "b".repeat(n) + "y/" + "c".repeat(n), true),
                new Case("/{a}x/{b}y/{c}", n -> "/" + "a".repeat(n) + "x/" + "b".repeat(n) + "/" + "c".repeat(n), false));
        for (Case c : cases) {
            ApiSurfaceRoute route = Fixtures.open(c.pattern());
            int base = 50_000;
            double small = timeOf(route, c.path().apply(base), c.matches());
            double doubled = timeOf(route, c.path().apply(2 * base), c.matches());
            double large = timeOf(route, c.path().apply(8 * base), c.matches());
            System.out.println(String.format(java.util.Locale.ROOT, "cost finding: %s, %s: n=%d %.3f ms, 2n %.3f ms, 8n %.3f ms",
                    c.pattern(), c.matches() ? "match" : "miss", base, small, doubled, large));
            assertThat(large / Math.max(small, 0.01)).as("time for 8 times the path, %s", c.pattern()).isLessThan(32.0);
            assertThat(doubled / Math.max(small, 0.01)).as("time for twice the path, %s", c.pattern()).isLessThan(8.0);
        }
    }

    @Test
    @DisplayName("README: parameters that can share text grow polynomially with the shared segment, with a degree no higher than their number")
    void sharedTextCostIsPolynomial() {
        record Case(String pattern, int parameters, int base) { }
        for (Case c : List.of(new Case("/{a}{b}x", 2, 400), new Case("/{a}-{b}x", 2, 400), new Case("/{a}{b}{c}x", 3, 60))) {
            ApiSurfaceRoute route = Fixtures.open(c.pattern());
            String unit = c.pattern().contains("-") ? "-" : "a";
            double small = timeOf(route, "/" + unit.repeat(c.base()), false);
            double large = timeOf(route, "/" + unit.repeat(4 * c.base()), false);
            double degree = Math.log(large / Math.max(small, 0.001)) / Math.log(4);
            System.out.println(String.format(java.util.Locale.ROOT, "cost finding: %s, a miss: n=%d %.3f ms, 4n %.3f ms, degree %.2f",
                    c.pattern(), c.base(), small, large, degree));
            assertThat(large / Math.max(small, 0.001)).as("time for 4 times the shared segment, %s", c.pattern())
                    .isLessThan(Math.pow(4, c.parameters() + 1));
        }
    }

    private static double timeOf(ApiSurfaceRoute route, String path, boolean matches) {
        Runnable work = () -> assertThat(route.matchesPath(path)).isEqualTo(matches);
        for (int i = 0; i < 20; i++) {
            work.run();
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 15; i++) {
            long start = System.nanoTime();
            work.run();
            best = Math.min(best, (System.nanoTime() - start) / 1e6);
        }
        return best;
    }
}
