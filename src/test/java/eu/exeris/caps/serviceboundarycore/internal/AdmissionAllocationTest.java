package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Allocation on the admission success path")
class AdmissionAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int MEASURED = 200_000;

    /** An exchange whose answer path allocates nothing, so the measurement sees only the registry. */
    private static final class CountingExchange implements HttpExchange {
        private final HttpRequest request =
                HttpRequest.noBody(HttpMethod.GET, "/api/open", HttpVersion.HTTP_1_1, List.of());
        int answers;

        @Override public HttpRequest request() { return request; }
        @Override public void respond(HttpResponse response) { answers++; }
        @Override public void respond(HttpTypedResponse typedResponse) { answers++; }
        @Override public void respond(HttpStatus status, Object body) { answers++; }
        @Override public void respond(HttpStatus status) { answers++; }
    }

    private static DefaultApiSurfaceRegistry registry(boolean strict) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry(strict);
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/api/open", "Open#get",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        registry.freeze();
        return registry;
    }

    /** Bytes the calling thread allocated per request over the measured window, after a JIT warm-up. */
    private static double bytesPerRequest(DefaultApiSurfaceRegistry registry, String label) throws Exception {
        CountingExchange exchange = new CountingExchange();
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

    @Test
    @DisplayName("handle() of a permitAll route allocates no more per request under the strict default than under the opt-out")
    void strictDefaultAllocatesNoMoreThanOptOut() throws Exception {
        // The first pair only brings both code paths to their compiled steady state; the second pair is compared.
        bytesPerRequest(registry(false), "WarmLenient");
        bytesPerRequest(registry(true), "WarmStrict");
        double lenient = bytesPerRequest(registry(false), "Lenient");
        double strict = bytesPerRequest(registry(true), "Strict");
        double lenientAgain = bytesPerRequest(registry(false), "LenientAgain");
        double strictAgain = bytesPerRequest(registry(true), "StrictAgain");

        // One byte per request is the kernel TCK's own limit for "no per-iteration allocation"; the two paths
        // differ by a volatile read, which allocates nothing, so they must agree to within it.
        assertThat(strict - lenient).as("strict %.2f vs opt-out %.2f bytes/request", strict, lenient).isLessThan(1.0);
        assertThat(strictAgain - lenientAgain).as("strict %.2f vs opt-out %.2f bytes/request", strictAgain, lenientAgain)
                .isLessThan(1.0);
    }

    private static DefaultApiSurfaceRegistry registryWithFilters(int filters) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        for (int i = 0; i < filters; i++) {
            registry.registerFilter(new Probes.CountingFilter("pass-" + i, AdmissionPhase.AUTHENTICATION,
                    (ex, m) -> true));
        }
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, "/api/open", "Open#get",
                List.of(RouteRequirement.permitAll()), ex -> ex.respond(HttpStatus.OK)));
        registry.freeze();
        return registry;
    }

    @Test
    @DisplayName("the allocation a pass-through filter adds per request is one constant, for a chain of 1, 2 and 4 filters")
    void perFilterAllocationIsConstant() throws Exception {
        // Warm every shape first so each measured one runs compiled.
        for (int n : new int[] {1, 2, 4}) {
            bytesPerRequest(registryWithFilters(n), "WarmFilters" + n);
        }
        double[] bytes = new double[3];
        double worstDisagreement = 0;
        for (int round = 0; round < 3; round++) {
            bytes[0] = bytesPerRequest(registryWithFilters(1), "Filters1Round" + round);
            bytes[1] = bytesPerRequest(registryWithFilters(2), "Filters2Round" + round);
            bytes[2] = bytesPerRequest(registryWithFilters(4), "Filters4Round" + round);
            double firstStep = bytes[1] - bytes[0];
            double perFilterOverTwo = (bytes[2] - bytes[1]) / 2.0;
            if (Boolean.getBoolean("boundary.alloc.print")) {
                System.out.printf("allocation round %d: 1 filter %.2f, 2 filters %.2f, 4 filters %.2f bytes/request; "
                        + "per filter %.2f (1->2) and %.2f (2->4)%n", round, bytes[0], bytes[1], bytes[2],
                        firstStep, perFilterOverTwo);
            }
            worstDisagreement = Math.max(worstDisagreement,
                    Math.abs(firstStep - perFilterOverTwo) / Math.min(firstStep, perFilterOverTwo));
        }

        // The two steps are never byte-identical: the filter list copy is an array padded to the object
        // alignment, so its size moves in steps. A per-filter cost that grew with the chain, which a read of
        // the answered flag per filter could cause only by allocating, would differ by far more than that.
        assertThat(worstDisagreement).as("relative disagreement between the 1->2 and the 2->4 per-filter step")
                .isLessThan(0.05);
    }
}
