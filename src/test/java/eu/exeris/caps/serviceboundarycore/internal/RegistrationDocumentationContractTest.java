package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.SpyFilter;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Documentation as contract: registration capture, the late literal route, the 404 and 405 path, the equal-value write")
class RegistrationDocumentationContractTest {

    private static final String REGISTRY = "src/main/java/eu/exeris/caps/serviceboundarycore/internal/DefaultApiSurfaceRegistry.java";
    private static final String ADMISSION = "src/main/java/eu/exeris/caps/serviceboundarycore/internal/DefaultAdmissionContext.java";

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());

    /** The text of a file with the comment markers removed and the whitespace collapsed. */
    private static String prose(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("(?m)^\\s*(/\\*\\*|\\*/|\\*)[ \\t]?", " ").replaceAll("\\s+", " ");
    }

    private List<String> serve(DefaultApiSurfaceRegistry registry) {
        trace.clear();
        registry.handle(ScriptedExchange.get("/p"));
        return List.copyOf(trace);
    }

    private DefaultApiSurfaceRegistry registry() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/p"));
        return registry;
    }

    // ---- the class Javadoc: the captured key and atomicity ---------------------------------------------------

    @Test
    @DisplayName("Javadoc: the sort key is the name and phase reported at registration, read once and never read again")
    void capturedKeySentences() throws IOException {
        assertThat(prose(REGISTRY))
                .contains("The sort key is the phase and name a filter reported when it was registered.")
                .contains("Both are read exactly once, inside {@code registerFilter} before anything is changed, "
                        + "and kept in an immutable {@code RegisteredFilter};")
                .contains("{@code name()} and {@code phase()} are never called on a registered filter again, so a filter "
                        + "that later reports another name or phase keeps its place and its name.")
                .contains("The order is not recomputed per request, and a rebuild sorts the captured values, not the filters.");

        DefaultApiSurfaceRegistry registry = registry();
        SpyFilter late = new SpyFilter("m-late", AdmissionPhase.AUTHORIZATION, trace);
        SpyFilter early = new SpyFilter("z-early", AdmissionPhase.PRE_ADMISSION, trace);
        registry.registerFilter(late);
        registry.registerFilter(early);
        assertThat(late.nameCalls.get() + late.phaseCalls.get() + early.nameCalls.get() + early.phaseCalls.get())
                .as("exactly one read of each").isEqualTo(4);

        late.reportsName("a-now-first").reportsPhase(AdmissionPhase.PRE_ADMISSION);
        early.reportsName("b").reportsPhase(AdmissionPhase.AUTHORIZATION);
        registry.registerFilter(new SpyFilter("a-between", AdmissionPhase.RATE_LIMITING, trace));
        registry.unregisterFilter(new SpyFilter("nobody", AdmissionPhase.RATE_LIMITING, trace));
        for (int i = 0; i < 20; i++) {
            assertThat(serve(registry)).containsExactly("z-early", "a-between", "m-late");
        }

        assertThat(late.calls()).isEqualTo(2);
        assertThat(early.calls()).isEqualTo(2);
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("m-late", AdmissionPhase.AUTHORIZATION, trace)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Javadoc: filters() is a fresh unmodifiable list of the execution order and exposes no live state")
    void filtersSnapshotSentence() throws IOException {
        assertThat(prose(REGISTRY)).contains(
                "{@link #filters()} returns a fresh unmodifiable list of that order and exposes no live state.");
        DefaultApiSurfaceRegistry registry = registry();
        SpyFilter a = new SpyFilter("a", AdmissionPhase.PRE_ADMISSION, trace);
        SpyFilter b = new SpyFilter("b", AdmissionPhase.AUTHORIZATION, trace);
        registry.registerFilter(b);
        registry.registerFilter(a);

        List<AdmissionFilter> first = registry.filters();
        List<AdmissionFilter> second = registry.filters();

        assertThat(first).isNotSameAs(second).containsExactly(a, b).isEqualTo(second);
        assertThatThrownBy(() -> first.add(a)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(first::clear).isInstanceOf(UnsupportedOperationException.class);
        registry.unregisterFilter(a);
        assertThat(first).as("a snapshot taken before the removal").containsExactly(a, b);
        assertThat(registry.filters()).containsExactly(b);
    }

    @Test
    @DisplayName("Javadoc: a registration that throws, at any point, leaves the list and the ordered array as they were")
    void atomicityHoldsForEveryThrowPoint() throws IOException {
        assertThat(prose(REGISTRY)).contains("{@code registerFilter} validates everything and builds the new array before "
                + "it changes the list or replaces {@code orderedFilters}, so a registration that throws, at any point, "
                + "leaves both as they were.");
        DefaultApiSurfaceRegistry registry = registry();
        SpyFilter kept = new SpyFilter("kept", AdmissionPhase.AUTHENTICATION, trace);
        registry.registerFilter(kept);
        List<SpyFilter> refused = List.of(
                new SpyFilter("x", AdmissionPhase.AUTHENTICATION, trace).throwsOnName(new IllegalStateException("name")),
                new SpyFilter("x", AdmissionPhase.AUTHENTICATION, trace).throwsOnPhase(new IllegalStateException("phase")),
                new SpyFilter("x", AdmissionPhase.AUTHENTICATION, trace).reportsName(" "),
                new SpyFilter("x", null, trace),
                new SpyFilter("kept", AdmissionPhase.PRE_ADMISSION, trace));
        for (SpyFilter filter : refused) {
            assertThatThrownBy(() -> registry.registerFilter(filter)).isInstanceOf(RuntimeException.class);
            assertThat(registry.filters()).containsExactly(kept);
            assertThat(serve(registry)).containsExactly("kept");
        }
        assertThatThrownBy(() -> registry.registerFilter(kept)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerFilter(null)).isInstanceOf(NullPointerException.class);
        registry.freeze();
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("y", AdmissionPhase.AUTHENTICATION, trace)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.filters()).containsExactly(kept);
        assertThat(serve(registry)).containsExactly("kept");
    }

    @Test
    @DisplayName("Javadoc: a registration that throws leaves both as they were, also when the log handler throws")
    void atomicityHoldsWhenTheLogHandlerThrows() {
        DefaultApiSurfaceRegistry registry = registry();
        Logger logger = Logger.getLogger(DefaultApiSurfaceRegistry.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousUseParent = logger.getUseParentHandlers();
        Handler failing = new Handler() {
            @Override public void publish(LogRecord record) { throw new IllegalStateException("log handler"); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        failing.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(failing);
        try {
            SpyFilter filter = new SpyFilter("logged", AdmissionPhase.AUTHENTICATION, trace);
            boolean threw = false;
            try {
                registry.registerFilter(filter);
            } catch (IllegalStateException e) {
                threw = true;
            }
            if (threw) {
                assertThat(registry.filters()).as("a registration that threw must have changed nothing").isEmpty();
                assertThat(serve(registry)).isEmpty();
            }
        } finally {
            logger.removeHandler(failing);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParent);
        }
    }

    // ---- README and interface: names are unique and non-blank --------------------------------------------------

    @Test
    @DisplayName("README: ordered by phase then name; names are unique and non-blank; a duplicate is refused")
    void readmeOrderAndUniqueness() throws IOException {
        assertThat(prose("README.md"))
                .contains("ordered by `AdmissionPhase`")
                .contains("Filter names are unique and non-blank; registering a duplicate is refused.");
        DefaultApiSurfaceRegistry registry = registry();
        registry.registerFilter(new SpyFilter("b", AdmissionPhase.AUTHENTICATION, trace));
        registry.registerFilter(new SpyFilter("a", AdmissionPhase.AUTHENTICATION, trace));
        registry.registerFilter(new SpyFilter("z", AdmissionPhase.PRE_ADMISSION, trace));

        assertThat(serve(registry)).containsExactly("z", "a", "b");
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("a", AdmissionPhase.AUTHORIZATION, trace)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.registerFilter(new SpyFilter("", AdmissionPhase.AUTHORIZATION, trace)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- ROADMAP: the late literal route and the 404 and 405 path ----------------------------------------------

    @Test
    @DisplayName("ROADMAP: a literal route registered after parameterised routes serves the same answers as one registered before them")
    void lateLiteralRouteSentences() throws IOException {
        assertThat(prose("ROADMAP.md"))
                .contains("A literal route pays nothing only when it is registered before the parameterised routes.")
                .contains("The match walks the routes in registration order, and every parameterised route of the same "
                        + "HTTP method scanned before the match runs its pattern, so a literal route registered after "
                        + "parameterised routes of its method pays for scanning each of them; one registered before "
                        + "them stops the walk first.");

        for (boolean literalFirst : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            List<String> handled = Collections.synchronizedList(new ArrayList<>());
            ApiSurfaceRoute literal = Fixtures.open("/api/open", ex -> {
                handled.add("literal");
                ex.respond(HttpStatus.OK);
            });
            if (literalFirst) {
                registry.registerRoute(literal);
            }
            registry.registerRoute(Fixtures.route(HttpMethod.POST, "/api/{id}", List.of(RouteRequirement.permitAll()),
                    ex -> {
                        handled.add("other method");
                        ex.respond(HttpStatus.OK);
                    }));
            registry.registerRoute(Fixtures.open("/api/{id}", ex -> {
                handled.add("parameterised");
                ex.respond(HttpStatus.OK);
            }));
            if (!literalFirst) {
                registry.registerRoute(literal);
            }

            registry.handle(ScriptedExchange.get("/api/open"));
            registry.handle(ScriptedExchange.get("/api/open?x=1"));
            registry.handle(ScriptedExchange.get("/api/other"));

            assertThat(handled).as("literal first = %s", literalFirst)
                    .containsExactly("literal", "literal", "parameterised");
        }
    }

    @Test
    @DisplayName("ROADMAP: the 404 and 405 answers; a 405 builds the Allow value of the allowed methods and carries it")
    void notFoundAndMethodNotAllowedSentences() throws IOException {
        assertThat(prose("ROADMAP.md")).contains("The 404 and 405 answers allocate too: both build the set of allowed "
                + "methods, and may cut a substring (when the request path has a query string) and run the pattern of "
                + "each parameterised route of any method, because the walk tests every route, literal ones included; "
                + "a 405 also builds the `Allow` value and the header list. Which of these the JIT removes varies, so "
                + "this page does not count them.");

        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.route(HttpMethod.GET, "/items/{id}", List.of(RouteRequirement.permitAll()),
                Fixtures.ok()));
        registry.registerRoute(Fixtures.route(HttpMethod.PUT, "/items/{id}", List.of(RouteRequirement.permitAll()),
                Fixtures.ok()));
        registry.registerRoute(Fixtures.route(HttpMethod.DELETE, "/items/all", List.of(RouteRequirement.permitAll()),
                Fixtures.ok()));

        ScriptedExchange plain = ScriptedExchange.of(HttpMethod.POST, "/items/7", List.of());
        ScriptedExchange withQuery = ScriptedExchange.of(HttpMethod.POST, "/items/7?x=1", List.of());
        ScriptedExchange literal = ScriptedExchange.of(HttpMethod.POST, "/items/all", List.of());
        ScriptedExchange missing = ScriptedExchange.of(HttpMethod.GET, "/nothing/7", List.of());
        ScriptedExchange missingWithQuery = ScriptedExchange.of(HttpMethod.GET, "/nothing/7?x=1", List.of());
        for (ScriptedExchange exchange : List.of(plain, withQuery, literal, missing, missingWithQuery)) {
            registry.handle(exchange);
        }

        assertThat(plain.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(withQuery.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(literal.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(allow(plain)).isEqualTo("GET, PUT");
        assertThat(allow(withQuery)).isEqualTo("GET, PUT");
        assertThat(allow(literal)).as("the literal path also matches the parameterised pattern").isEqualTo("GET, PUT, DELETE");
        // the walk consults the routes of every method: a method no route serves is told all of them
        ScriptedExchange otherMethod = ScriptedExchange.of(HttpMethod.PATCH, "/items/all", List.of());
        registry.handle(otherMethod);
        assertThat(otherMethod.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(allow(otherMethod)).as("the GET and PUT parameterised routes and the literal DELETE route")
                .isEqualTo("GET, PUT, DELETE");
        assertThat(missing.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        assertThat(missingWithQuery.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        assertThat(missing.responses()).as("a 404 carries no header list").allSatisfy(r -> assertThat(r).isNull());
    }

    private static String allow(ScriptedExchange exchange) {
        return exchange.responses().get(0).headers().stream()
                .filter(h -> h.name().equalsIgnoreCase("Allow")).map(HttpHeader::value).findFirst().orElseThrow();
    }

    // ---- DefaultAdmissionContext: the equal-value write ---------------------------------------------------------

    @Test
    @DisplayName("Javadoc: an attribute write of a value equal to the one held yields a new instance equal to the previous one")
    void equalValueWriteSentence() throws IOException {
        assertThat(prose(ADMISSION))
                .contains("{@link #requestContext()} returns the same instance until a write to the principal, session, "
                        + "tenant or attributes, and a new one after it.")
                .contains("Every such write counts, including an attribute write of a value equal to the one held, which "
                        + "yields a new instance equal to the previous one.");

        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        admission.attribute("k", "v");
        RequestContext held = admission.requestContext();
        assertThat(admission.requestContext()).isSameAs(held);

        admission.attribute("k", "v");
        RequestContext afterEqualWrite = admission.requestContext();

        assertThat(afterEqualWrite).isNotSameAs(held).isEqualTo(held);
        assertThat(afterEqualWrite.correlationId()).isEqualTo(held.correlationId());
        assertThat(afterEqualWrite.attributes()).isEqualTo(held.attributes());
        assertThat(admission.requestContext()).isSameAs(afterEqualWrite);
    }

    @Test
    @DisplayName("Javadoc: a context handed out keeps the state it was built from, also across an equal-value write")
    void handedOutContextKeepsItsState() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        admission.attribute("k", "v");
        RequestContext held = admission.requestContext();

        admission.attribute("k", "v");
        admission.attribute("k", "w");

        assertThat(held.attribute("k")).contains("v");
        assertThat(admission.requestContext().attribute("k")).contains("w");
        assertThatThrownBy(() -> held.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
