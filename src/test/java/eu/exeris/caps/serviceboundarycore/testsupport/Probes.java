package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Small hand-written filters, handlers and principals shared by the registry tests. */
public final class Probes {

    private Probes() { }

    /** A filter body: may respond, throw, and returns the admission verdict. */
    @FunctionalInterface
    public interface FilterBody {
        boolean run(HttpExchange exchange, RouteMatch match) throws Exception;
    }

    /** A filter that counts its invocations and delegates to a body. */
    public static final class CountingFilter implements AdmissionFilter {
        private final String name;
        private final AdmissionPhase phase;
        private final FilterBody body;
        public final AtomicInteger calls = new AtomicInteger();

        public CountingFilter(String name, AdmissionPhase phase, FilterBody body) {
            this.name = name;
            this.phase = phase;
            this.body = body;
        }

        @Override public String name() { return name; }

        @Override public AdmissionPhase phase() { return phase; }

        @Override
        public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
            calls.incrementAndGet();
            try {
                return body.run(exchange, routeMatch);
            } catch (RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (Exception checked) {
                throw new IllegalStateException(checked);
            }
        }
    }

    public static CountingFilter filter(String name, FilterBody body) {
        return new CountingFilter(name, AdmissionPhase.AUTHENTICATION, body);
    }

    /** A principal holding the given scopes. */
    public record Principal(Set<String> scopes) implements PrincipalContext {
        public Principal() {
            this(Set.of());
        }

        @Override public UUID principalId() { return new UUID(0L, 1L); }

        @Override public Optional<UUID> tenantId() { return Optional.empty(); }

        @Override public Set<String> roles() { return Set.of(); }
    }

    /** A {@link java.lang.VirtualMachineError} that is not one of the JDK's own. */
    public static final class CustomVmError extends VirtualMachineError {
        public CustomVmError(String message) {
            super(message);
        }
    }

    /** An ordinary {@link Error}. */
    public static final class CustomError extends Error {
        public CustomError(String message) {
            super(message);
        }
    }
}
