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
    @DisplayName("README: a route without parameters is exact and wins over a route with parameters, whichever was registered first; among routes with parameters the first registered that matches wins")
    void readmeSelectionSentence() throws IOException {
        assertThat(prose("README.md")).contains("**Route matching.** A route without path parameters is exact and wins over a route with "
                + "parameters, whichever was registered first; among routes with parameters the first registered that matches wins.");
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
    @DisplayName("README: a {name} stands for one or more characters other than a slash, without decoding, and need not be a whole segment")
    void readmeParameterSentence() throws IOException {
        assertThat(prose("README.md")).contains("A `{name}` segment stands for one or more characters other than `/`, without decoding.");
        ApiSurfaceRoute whole = Fixtures.open("/a/{x}");
        assertThat(whole.extractPathParams("/a/%41%2F")).isEqualTo(Map.of("x", "%41%2F"));
        assertThat(whole.matchesPath("/a/")).as("one or more characters").isFalse();
        assertThat(whole.matchesPath("/a/b/c")).as("no slash").isFalse();
        ApiSurfaceRoute part = Fixtures.open("/files/{name}.{ext}");
        assertThat(part.extractPathParams("/files/report.pdf")).as("a parameter inside a segment").isEqualTo(Map.of("name", "report", "ext", "pdf"));
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
    @DisplayName("README: a route with parameters that matches allocates one String per distinct name and one immutable map, which a RouteMatch keeps without a copy")
    void readmeAllocationSentence() throws IOException {
        assertThat(prose("README.md")).contains("Matching allocates nothing for a route that does not match; a route with parameters that "
                + "matches allocates one `String` per distinct parameter name and one immutable map that holds them, and none of this "
                + "uses a regular expression.");
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
    @DisplayName("README and ROADMAP: no method that matches a request path, in the pattern, the route or the registry, calls java.util.regex")
    void theRequestPathUsesNoRegularExpression() throws IOException {
        assertThat(regexUses(PathPattern.class, Set.of("matches", "match", "region", "regionStart", "regionLimit", "startsWithSlash",
                "startPosition", "matchesRegion", "matchParameters", "parameterEnd", "valueLimit", "splitsPair"))).isEmpty();
        assertThat(regexUses(ApiSurfaceRoute.class, Set.of("matchesPath", "extractPathParams", "normalizePath", "matchesMethod"))).isEmpty();
        assertThat(regexUses(DefaultApiSurfaceRegistry.class, Set.of("findMatch", "servesPath", "matchesLiteral", "handle", "matchRoute",
                "routes", "routeCount"))).isEmpty();
        assertThat(regexUses(PathPattern.class, Set.of("<init>")))
                .as("the check can see a call to the regular expression classes: the constructor makes some")
                .isNotEmpty();
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
    @DisplayName("Javadoc: a parameter takes the longest value for which the rest still matches, and never ends inside a surrogate pair")
    void pathPatternGreedySentences() throws IOException {
        assertThat(prose(PATH_PATTERN))
                .contains("giving each parameter in turn the longest value for which the rest still matches.")
                .contains("A parameter never ends inside a surrogate pair, because a code point is the unit of that expression.");
        assertThat(Fixtures.open("/{a}-{b}").extractPathParams("/1-2-3")).isEqualTo(Map.of("a", "1-2", "b", "3"));
        assertThat(Fixtures.open("/{a}{b}").extractPathParams("/abc")).isEqualTo(Map.of("a", "ab", "b", "c"));
        assertThat(Fixtures.open("/{a}{b}").matchesPath("/😀")).isFalse();
    }
}
