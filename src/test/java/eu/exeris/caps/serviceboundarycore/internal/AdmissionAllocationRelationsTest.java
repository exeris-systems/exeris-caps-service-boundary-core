package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
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

    @Test
    @DisplayName("finding: the literal request with parameterised routes registered before it, with and without a query string")
    void literalFigureWithParameterisedRoutesRegisteredBeforeIt() throws Exception {
        for (int n : new int[] {0, 5, 20}) {
            steady(literalWithParameterised(n, false), "/api/open", "WarmBefore" + n);
        }
        double none = measure(literalWithParameterised(0, false), "/api/open", "Before0");
        double five = measure(literalWithParameterised(5, false), "/api/open", "Before5");
        double twenty = measure(literalWithParameterised(20, false), "/api/open", "Before20");
        double twentyQuery = measure(literalWithParameterised(20, false), "/api/open?x=1", "Before20Query");
        report("FINDING literal registered last, after 0, 5, 20 parameterised routes; 20 with a query string", none, five, twenty,
                twentyQuery);
        // Not asserted: whether a literal request pays for the parameterised scan is the finding itself.
        assertThat(none).isPositive();
    }

    @Test
    @DisplayName("roadmap: a request for a parameterised route allocates more, and more for each parameterised route scanned before it")
    void parameterisedRoutesCostMoreAndGrowWithTheScan() throws Exception {
        DefaultApiSurfaceRegistry literal = literalRegistry();
        steady(literal, "/api/open", "WarmLiteralBase");
        for (int n : new int[] {0, 5, 20}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            for (int i = 0; i < n; i++) {
                registry.registerRoute(Fixtures.open("/p" + i + "/{id}"));
            }
            registry.registerRoute(Fixtures.open("/items/{id}"));
            registry.freeze();
            steady(registry, "/items/7", "WarmParam" + n);
        }
        double literalBytes = measure(literal, "/api/open", "LiteralBase");
        double[] bytes = new double[3];
        int[] counts = {0, 5, 20};
        for (int k = 0; k < counts.length; k++) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            for (int i = 0; i < counts[k]; i++) {
                registry.registerRoute(Fixtures.open("/p" + i + "/{id}"));
            }
            registry.registerRoute(Fixtures.open("/items/{id}"));
            registry.freeze();
            bytes[k] = measure(registry, "/items/7", "Param" + counts[k]);
        }
        report("literal; parameterised route after 0, 5, 20 other parameterised routes", literalBytes, bytes[0], bytes[1], bytes[2]);
        assertThat(bytes[0]).isGreaterThan(literalBytes);
        assertThat(bytes[1]).isGreaterThan(bytes[0]);
        assertThat(bytes[2]).isGreaterThan(bytes[1]);
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
}
