package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relations between the allocations of admission scenarios. The absolute figures depend on how the JIT compiled the
 * request path in the surefire JVM and are printed, never asserted; the relations hold in every compilation shape
 * because the compared scenarios run the same code up to a difference that allocates nothing, or by a known amount.
 */
@DisplayName("Allocation relations on the admission path")
class AdmissionAllocationRelationsTest {

    private static final int WARMUP = 50_000;
    private static final int MEASURED = 200_000;
    /** The kernel TCK's own limit for "no per-iteration allocation". */
    private static final double ONE_BYTE = 1.0;

    private static final class CountingExchange implements HttpExchange {
        private final HttpRequest request;
        long answers;

        CountingExchange(String path) {
            this.request = HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of());
        }

        @Override public HttpRequest request() { return request; }
        @Override public void respond(HttpResponse response) { answers++; }
        @Override public void respond(HttpTypedResponse typedResponse) { answers++; }
        @Override public void respond(HttpStatus status, Object body) { answers++; }
        @Override public void respond(HttpStatus status) { answers++; }
    }

    private static double measure(DefaultApiSurfaceRegistry registry, String path, String label) throws Exception {
        CountingExchange exchange = new CountingExchange(path);
        JfrAllocationMonitor.Config config = new JfrAllocationMonitor.Config(
                "serviceboundarycore", label, WARMUP, MEASURED, JfrAllocationMonitor.Contract.unspecified());
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                registry.handle(exchange);
            }
        });
        assertThat(exchange.answers).as("every iteration was answered").isEqualTo(WARMUP + MEASURED);
        assertThat(result.allocatedBytesDelta()).isNotEqualTo(JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE);
        return (double) result.allocatedBytesDelta() / result.hotPathIterations();
    }

    /** Measures twice and returns the second figure, so that the first run only brings the code to its compiled shape. */
    private static double steady(DefaultApiSurfaceRegistry registry, String path, String label) throws Exception {
        measure(registry, path, label + "Warm");
        return measure(registry, path, label);
    }

    private static void report(String what, double... bytes) {
        StringBuilder line = new StringBuilder("allocation finding: ").append(what).append(':');
        for (double b : bytes) {
            line.append(String.format(" %.2f", b));
        }
        System.out.println(line.append(" bytes/request"));
    }

    private static DefaultApiSurfaceRegistry literalRegistry() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/api/open"));
        registry.freeze();
        return registry;
    }

    private static DefaultApiSurfaceRegistry registryWithFilters(Consumer<RequestContext> body, int filters) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        for (int i = 0; i < filters; i++) {
            registry.registerFilter(Fixtures.filter("f" + i, AdmissionPhase.AUTHENTICATION,
                    m -> body.accept(RequestContext.current())));
        }
        registry.registerRoute(Fixtures.open("/api/open"));
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("(i) a literal request with a query string or a trailing slash allocates what the plain literal request allocates")
    void queryAndTrailingSlashAllocateNothingExtra() throws Exception {
        DefaultApiSurfaceRegistry registry = literalRegistry();
        steady(registry, "/api/open", "WarmPlain");
        steady(registry, "/api/open?x=1&y=2", "WarmQuery");
        steady(registry, "/api/open/", "WarmSlash");
        steady(registry, " /api/open/?x=1", "WarmBoth");
        for (int round = 0; round < 3; round++) {
            double plain = measure(registry, "/api/open", "Plain" + round);
            double query = measure(registry, "/api/open?x=1&y=2", "Query" + round);
            double slash = measure(registry, "/api/open/", "Slash" + round);
            double both = measure(registry, " /api/open/?x=1", "Both" + round);
            report("plain, query, trailing slash, whitespace+slash+query (round " + round + ")", plain, query, slash, both);
            assertThat(query - plain).as("query vs plain").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(slash - plain).as("trailing slash vs plain").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(both - plain).as("whitespace, slash and query vs plain").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(plain).as("an anonymous literal request still allocates, as the roadmap states").isPositive();
        }
    }

    @Test
    @DisplayName("(ii) the per-filter increment of pass-through filters is constant from 2 to 4 and from 4 to 8 filters")
    void perFilterIncrementIsConstant() throws Exception {
        Consumer<RequestContext> passThrough = ctx -> { };
        for (int n : new int[] {2, 4, 8}) {
            steady(registryWithFilters(passThrough, n), "/api/open", "WarmFilters" + n);
        }
        double worst = 0;
        for (int round = 0; round < 3; round++) {
            double two = measure(registryWithFilters(passThrough, 2), "/api/open", "F2R" + round);
            double four = measure(registryWithFilters(passThrough, 4), "/api/open", "F4R" + round);
            double eight = measure(registryWithFilters(passThrough, 8), "/api/open", "F8R" + round);
            double lower = (four - two) / 2.0;
            double upper = (eight - four) / 4.0;
            report("2, 4, 8 filters (round " + round + "); per filter " + String.format("%.2f and %.2f", lower, upper),
                    two, four, eight);
            worst = Math.max(worst, Math.abs(lower - upper) / Math.min(lower, upper));
        }
        assertThat(worst).as("relative disagreement between the per-filter steps").isLessThan(0.05);
    }

    @Test
    @DisplayName("(iv) a request that reads the correlation id allocates more than one that does not, by the same amount however often it reads")
    void correlationIdCostsOncePerRequest() throws Exception {
        steady(registryWithFilters(ctx -> { }, 1), "/api/open", "WarmNoRead");
        steady(registryWithFilters(RequestContext::correlationId, 1), "/api/open", "WarmRead");
        for (int round = 0; round < 3; round++) {
            double none = measure(registryWithFilters(ctx -> { }, 1), "/api/open", "NoRead" + round);
            double once = measure(registryWithFilters(RequestContext::correlationId, 1), "/api/open", "ReadOnce" + round);
            double fiveTimes = measure(registryWithFilters(ctx -> {
                for (int i = 0; i < 5; i++) {
                    ctx.correlationId();
                }
            }, 1), "/api/open", "ReadFive" + round);
            double twoFilters = measure(registryWithFilters(RequestContext::correlationId, 2), "/api/open", "ReadTwoFilters" + round);
            double twoFiltersNone = measure(registryWithFilters(ctx -> { }, 2), "/api/open", "NoReadTwoFilters" + round);
            report("no read, one read, five reads, then two filters reading and not reading (round " + round + ")",
                    none, once, fiveTimes, twoFilters, twoFiltersNone);
            assertThat(once).as("one read allocates more than none").isGreaterThan(none);
            assertThat(fiveTimes - none).as("five reads cost what one read costs").isBetween(once - none - ONE_BYTE, once - none + ONE_BYTE);
            assertThat(twoFilters - twoFiltersNone).as("two filters reading the same id cost what one read costs")
                    .isBetween(once - none - ONE_BYTE, once - none + ONE_BYTE);
        }
    }

    @Test
    @DisplayName("(v) repeated requestContext() calls within one filter add nothing after the first")
    void repeatedRequestContextCallsAddNothing() throws Exception {
        steady(registryWithFiltersCallingRequestContext(1), "/api/open", "WarmOnce");
        steady(registryWithFiltersCallingRequestContext(10), "/api/open", "WarmTen");
        for (int round = 0; round < 3; round++) {
            double once = measure(registryWithFiltersCallingRequestContext(1), "/api/open", "RcOnce" + round);
            double ten = measure(registryWithFiltersCallingRequestContext(10), "/api/open", "RcTen" + round);
            double hundred = measure(registryWithFiltersCallingRequestContext(100), "/api/open", "RcHundred" + round);
            report("requestContext() once, ten times, a hundred times (round " + round + ")", once, ten, hundred);
            assertThat(ten - once).isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(hundred - once).isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }

    private static DefaultApiSurfaceRegistry registryWithFiltersCallingRequestContext(int calls) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerFilter(Fixtures.filter("rc", AdmissionPhase.AUTHENTICATION, m -> {
            AdmissionContext admission = AdmissionContext.current();
            for (int i = 0; i < calls; i++) {
                admission.requestContext();
            }
        }));
        registry.registerRoute(Fixtures.open("/api/open"));
        registry.freeze();
        return registry;
    }

    private static DefaultApiSurfaceRegistry literalWithParameterised(int parameterised, boolean literalFirst) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        if (literalFirst) {
            registry.registerRoute(Fixtures.open("/api/open"));
        }
        for (int i = 0; i < parameterised; i++) {
            registry.registerRoute(Fixtures.open("/p" + i + "/{id}"));
        }
        if (!literalFirst) {
            registry.registerRoute(Fixtures.open("/api/open"));
        }
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("(vi) the literal-route figure with parameterised routes registered after it is the same for 0, 5 and 20 of them")
    void literalFigureIgnoresParameterisedRoutesRegisteredAfterIt() throws Exception {
        for (int n : new int[] {0, 5, 20}) {
            steady(literalWithParameterised(n, true), "/api/open", "WarmAfter" + n);
        }
        for (int round = 0; round < 3; round++) {
            double none = measure(literalWithParameterised(0, true), "/api/open", "After0R" + round);
            double five = measure(literalWithParameterised(5, true), "/api/open", "After5R" + round);
            double twenty = measure(literalWithParameterised(20, true), "/api/open", "After20R" + round);
            report("literal registered first, then 0, 5, 20 parameterised routes (round " + round + ")", none, five, twenty);
            assertThat(five - none).isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(twenty - none).isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }

    /** Parameterised routes that the request {@code /items/7} reaches the parameter of and then fails on. */
    private static DefaultApiSurfaceRegistry itemsAfterMisses(int misses) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        for (int i = 0; i < misses; i++) {
            registry.registerRoute(Fixtures.open("/items/{id}/p" + i));
        }
        registry.registerRoute(Fixtures.open("/items/{id}"));
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("roadmap: a request for a parameterised route allocates more than one for a literal route, and the same however many parameterised routes it tested and missed before")
    void parameterisedHitCostsMoreAndIgnoresMissedRoutes() throws Exception {
        DefaultApiSurfaceRegistry literal = literalRegistry();
        steady(literal, "/api/open", "WarmLiteralBase");
        int[] counts = {0, 5, 20};
        for (int n : counts) {
            steady(itemsAfterMisses(n), "/items/7", "WarmParam" + n);
        }
        for (int round = 0; round < 3; round++) {
            double literalBytes = measure(literal, "/api/open", "LiteralBase" + round);
            double[] bytes = new double[counts.length];
            for (int k = 0; k < counts.length; k++) {
                bytes[k] = measure(itemsAfterMisses(counts[k]), "/items/7", "Param" + counts[k] + "R" + round);
            }
            report("literal; parameterised hit after 0, 5, 20 missed parameterised routes (round " + round + ")",
                    literalBytes, bytes[0], bytes[1], bytes[2]);
            assertThat(bytes[0]).as("a parameterised hit allocates its values and map").isGreaterThan(literalBytes + ONE_BYTE);
            assertThat(bytes[1] - bytes[0]).as("5 missed routes add nothing").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(bytes[2] - bytes[0]).as("20 missed routes add nothing").isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }

    @Test
    @DisplayName("finding: the 404 answer, with and without a query string (shape dependent, reported only)")
    void notFoundAllocates() throws Exception {
        DefaultApiSurfaceRegistry registry = literalRegistry();
        steady(registry, "/missing", "WarmMissing");
        steady(registry, "/missing?x=1", "WarmMissingQuery");
        double missing = measure(registry, "/missing", "Missing");
        double missingQuery = measure(registry, "/missing?x=1", "MissingQuery");
        report("404, 404 with a query string", missing, missingQuery);
        assertThat(missing).isGreaterThanOrEqualTo(0.0);
    }

    // ---- what parameterised routes registered beside a literal route or a missing path cost --------------------

    private static final int PARAMETERISED = 4;

    /** A literal GET route and {@code PARAMETERISED} parameterised routes of the given method, in the given order. */
    private static DefaultApiSurfaceRegistry literalBesideParameterised(HttpMethod parameterisedMethod, boolean literalFirst) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        if (literalFirst) {
            registry.registerRoute(Fixtures.open("/api/open"));
        }
        for (int i = 0; i < PARAMETERISED; i++) {
            registry.registerRoute(Fixtures.route(parameterisedMethod, "/p" + i + "/{id}",
                    List.of(RouteRequirement.permitAll()), Fixtures.ok()));
        }
        if (!literalFirst) {
            registry.registerRoute(Fixtures.open("/api/open"));
        }
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("ROADMAP: a literal route's request allocates the same whatever parameterised routes, of any HTTP method, are registered before or after it")
    void parameterisedRoutesAddNothingToALiteralRequest() throws Exception {
        DefaultApiSurfaceRegistry bare = literalRegistry();
        DefaultApiSurfaceRegistry otherMethod = literalBesideParameterised(HttpMethod.POST, false);
        DefaultApiSurfaceRegistry sameMethod = literalBesideParameterised(HttpMethod.GET, false);
        DefaultApiSurfaceRegistry sameMethodAfter = literalBesideParameterised(HttpMethod.GET, true);
        for (DefaultApiSurfaceRegistry registry : List.of(bare, otherMethod, sameMethod, sameMethodAfter)) {
            steady(registry, "/api/open", "WarmScan");
        }
        for (int round = 0; round < 3; round++) {
            double base = measure(bare, "/api/open", "ScanBare" + round);
            double other = measure(otherMethod, "/api/open", "ScanOther" + round);
            double same = measure(sameMethod, "/api/open", "ScanSame" + round);
            double after = measure(sameMethodAfter, "/api/open", "ScanAfter" + round);
            report("literal alone, after " + PARAMETERISED + " parameterised routes of another method, of the same method, "
                    + "before them (round " + round + ")", base, other, same, after);
            assertThat(other - base).as("another method, registered before").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(same - base).as("the same method, registered before").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(after - base).as("registered after").isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }

    @Test
    @DisplayName("ROADMAP: the 404 walk allocates the same with parameterised routes of any HTTP method as without, and still consults the routes of every method")
    void notFoundWalkAllocatesNothingForParameterisedRoutes() throws Exception {
        DefaultApiSurfaceRegistry bare = literalRegistry();
        DefaultApiSurfaceRegistry otherMethod = literalBesideParameterised(HttpMethod.POST, false);
        DefaultApiSurfaceRegistry sameMethod = literalBesideParameterised(HttpMethod.GET, false);
        for (DefaultApiSurfaceRegistry registry : List.of(bare, otherMethod, sameMethod)) {
            steady(registry, "/nothing/here", "WarmNotFound");
        }
        for (int round = 0; round < 3; round++) {
            double base = measure(bare, "/nothing/here", "NotFoundBare" + round);
            double other = measure(otherMethod, "/nothing/here", "NotFoundOther" + round);
            double same = measure(sameMethod, "/nothing/here", "NotFoundSame" + round);
            report("404 with the literal alone, with " + PARAMETERISED + " parameterised routes of another method, of the "
                    + "same method (round " + round + ")", base, other, same);
            assertThat(other - base).as("parameterised routes of another method").isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(same - base).as("parameterised routes of the same method").isBetween(-ONE_BYTE, ONE_BYTE);
        }
        // the walk consults the routes of every method: a path only a POST route with parameters serves is a 405
        ScriptedExchange postOnly = ScriptedExchange.get("/p2/7");
        literalBesideParameterised(HttpMethod.POST, false).handle(postOnly);
        assertThat(postOnly.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(postOnly.responses().get(0).headers()).anySatisfy(h -> {
            assertThat(h.name()).isEqualToIgnoringCase("Allow");
            assertThat(h.value()).isEqualTo("POST");
        });
        ScriptedExchange nowhere = ScriptedExchange.get("/nothing/here");
        literalBesideParameterised(HttpMethod.POST, false).handle(nowhere);
        assertThat(nowhere.statuses()).containsExactly(HttpStatus.NOT_FOUND);
    }

    // ---- what matching itself allocates ---------------------------------------------------------------------------

    private static boolean sink;

    private static double measureMatching(java.util.function.Supplier<Object> work, String label) throws Exception {
        JfrAllocationMonitor.Config config = new JfrAllocationMonitor.Config(
                "serviceboundarycore", label, WARMUP, MEASURED, JfrAllocationMonitor.Contract.unspecified());
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            boolean seen = false;
            for (int i = 0; i < iterations; i++) {
                seen ^= work.get() == null;
            }
            sink = seen;
        });
        assertThat(result.allocatedBytesDelta()).isNotEqualTo(JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE);
        return (double) result.allocatedBytesDelta() / result.hotPathIterations();
    }

    @Test
    @DisplayName("ROADMAP: a parameterised route that does not match allocates nothing, whether it fails on its literal text, on a parameter or at the end")
    void parameterisedMissAllocatesNothing() throws Exception {
        PathPattern pattern = new PathPattern("/items/{id}/parts/{part}x");
        String[] misses = {"/other/7/parts/b", "/items//parts/b", "/items/7/parts/", "/items/7/parts/bb", "/items/7/parts/b/",
                "/items/7/parts/\uD83D\uDE00y", "/items/7", "/items/7?/parts/bx", " /items/7/parts/by?x=1 "};
        for (String path : misses) {
            assertThat(pattern.matches(path, path.indexOf('?') >= 0 ? path.indexOf('?') : path.length())).as(path).isFalse();
        }
        for (int round = 0; round < 3; round++) {
            double[] figures = new double[misses.length];
            for (int i = 0; i < misses.length; i++) {
                String path = misses[i];
                int end = path.indexOf('?') >= 0 ? path.indexOf('?') : path.length();
                figures[i] = Math.max(measureMatching(() -> pattern.match(path, end), "MissMatch" + i + "R" + round),
                        measureMatching(() -> pattern.matches(path, end) ? "hit" : null, "MissMatches" + i + "R" + round));
            }
            report("a miss of match() and of matches(), nine paths (round " + round + ")", figures);
            for (int i = 0; i < misses.length; i++) {
                assertThat(figures[i]).as("a miss for %s", misses[i]).isLessThan(ONE_BYTE);
            }
        }
        PathPattern adjacent = new PathPattern("/{a}{b}{c}x");
        for (int round = 0; round < 3; round++) {
            double figure = measureMatching(() -> adjacent.match("/aaaaaaaaaaaa", 13), "MissAdjacent" + round);
            report("a miss of three adjacent parameters (round " + round + ")", figure);
            assertThat(figure).isLessThan(ONE_BYTE);
        }
    }

    private static DefaultApiSurfaceRegistry registryOf(String pattern) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open(pattern));
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("ROADMAP: a parameterised hit allocates more for each distinct parameter name, and a repeated name counts once")
    void parameterisedHitGrowsWithDistinctNames() throws Exception {
        String[] patterns = {"/a/{p0}", "/a/{p0}/{p1}", "/a/{p0}/{p1}/{p2}", "/a/{p0}/{p1}/{p2}/{p3}", "/a/{p0}/{p1}/{p2}/{p3}/{p4}",
                "/a/{p0}/{p1}/{p2}/{p3}/{p4}/{p5}"};
        String[] paths = {"/a/v", "/a/v/v", "/a/v/v/v", "/a/v/v/v/v", "/a/v/v/v/v/v", "/a/v/v/v/v/v/v"};
        for (int k = 0; k < patterns.length; k++) {
            steady(registryOf(patterns[k]), paths[k], "WarmNames" + k);
        }
        steady(registryOf("/a/{p}/{p}"), "/a/v/w", "WarmRepeated");
        steady(registryOf("/a/{p}"), "/a/w", "WarmOnce");
        for (int round = 0; round < 3; round++) {
            double[] bytes = new double[patterns.length];
            for (int k = 0; k < patterns.length; k++) {
                bytes[k] = measure(registryOf(patterns[k]), paths[k], "Names" + (k + 1) + "R" + round);
            }
            double repeated = measure(registryOf("/a/{p}/{p}"), "/a/v/w", "RepeatedR" + round);
            double once = measure(registryOf("/a/{p}"), "/a/w", "OnceR" + round);
            report("1 to 6 distinct names (round " + round + ")", bytes);
            report("a name used twice, the same name used once (round " + round + ")", repeated, once);
            for (int k = 1; k < bytes.length; k++) {
                assertThat(bytes[k]).as("%d distinct names allocate more than %d", k + 1, k).isGreaterThan(bytes[k - 1] + ONE_BYTE);
            }
            assertThat(repeated - once).as("a repeated name is read once").isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }

    @Test
    @DisplayName("ROADMAP: a query string, a trailing slash or surrounding white space adds nothing to a parameterised hit")
    void queryAndTrailingSlashAddNothingToAParameterisedHit() throws Exception {
        DefaultApiSurfaceRegistry registry = registryOf("/items/{id}/parts/{part}");
        String[] paths = {"/items/7/parts/b", "/items/7/parts/b?x=1&y=2", "/items/7/parts/b/", " /items/7/parts/b/?x=1", "items/7/parts/b"};
        for (String path : paths) {
            steady(registry, path, "WarmHit");
        }
        for (int round = 0; round < 3; round++) {
            double[] bytes = new double[paths.length];
            for (int i = 0; i < paths.length; i++) {
                bytes[i] = measure(registry, paths[i], "Hit" + i + "R" + round);
            }
            report("parameterised hit: plain, query, trailing slash, white space and slash and query, no leading slash (round "
                    + round + ")", bytes);
            for (int i = 1; i < paths.length; i++) {
                assertThat(bytes[i] - bytes[0]).as(paths[i]).isBetween(-ONE_BYTE, ONE_BYTE);
            }
        }
    }

    @Test
    @DisplayName("ROADMAP: a request for a literal route allocates the same with 0, 5 and 20 parameterised routes of its method registered before it, and a 404 the same with 0 and 20")
    void literalAndNotFoundFiguresIgnoreParameterisedRoutes() throws Exception {
        int[] counts = {0, 5, 20};
        for (int n : counts) {
            steady(literalWithParameterised(n, false), "/api/open", "WarmLiteral" + n);
            steady(literalWithParameterised(n, false), "/nothing/7", "WarmMissing" + n);
        }
        for (int round = 0; round < 3; round++) {
            double[] literal = new double[counts.length];
            double[] missing = new double[counts.length];
            for (int k = 0; k < counts.length; k++) {
                literal[k] = measure(literalWithParameterised(counts[k], false), "/api/open", "LiteralBefore" + counts[k] + "R" + round);
                missing[k] = measure(literalWithParameterised(counts[k], false), "/nothing/7", "MissingWith" + counts[k] + "R" + round);
            }
            report("literal request after 0, 5, 20 parameterised routes (round " + round + ")", literal);
            report("404 with 0, 5, 20 parameterised routes (round " + round + ")", missing);
            assertThat(literal[1] - literal[0]).isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(literal[2] - literal[0]).isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(missing[1] - missing[0]).isBetween(-ONE_BYTE, ONE_BYTE);
            assertThat(missing[2] - missing[0]).isBetween(-ONE_BYTE, ONE_BYTE);
        }
    }
}
