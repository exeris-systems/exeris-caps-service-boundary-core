package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.ScopedValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard implementation of {@link RequestContext}, backed by {@link ScopedValue}.
 *
 * <p>Immutable and thread-safe. Context is propagated strictly through virtual thread
 * boundaries using {@link ScopedValue}, ensuring zero thread-local leaks and hard tenant
 * isolation per ADR-012.
 *
 * @since 0.1.0
 */
public final class DefaultRequestContext implements RequestContext {

    private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

    private final String tenantId;
    private final String correlationId;
    private final PrincipalContext principal;
    private final Map<String, Object> attributes;

    private DefaultRequestContext(String tenantId,
                                  String correlationId,
                                  PrincipalContext principal,
                                  Map<String, Object> attributes) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.principal = principal;
        this.attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public static RequestContext current() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("No RequestContext is bound in the current scope");
        }
        return CURRENT.get();
    }

    public static boolean isBound() {
        return CURRENT.isBound();
    }

    public static void runWith(RequestContext context, Runnable action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        ScopedValue.where(CURRENT, context).run(action);
    }

    public static <R, X extends Throwable> R callWith(RequestContext context,
                                                       ScopedValue.CallableOp<? extends R, X> op) throws X {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(op, "op");
        return ScopedValue.where(CURRENT, context).call(op);
    }

    @Override
    public String tenantId() {
        return tenantId;
    }

    @Override
    public String correlationId() {
        return correlationId;
    }

    @Override
    public Optional<PrincipalContext> principal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public Optional<Object> attribute(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(attributes.get(key));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultRequestContext that)) return false;
        return tenantId.equals(that.tenantId) &&
                correlationId.equals(that.correlationId) &&
                Objects.equals(principal, that.principal) &&
                attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, correlationId, principal, attributes);
    }

    @Override
    public String toString() {
        return "RequestContext[tenantId=" + tenantId + ", correlationId=" + correlationId +
                ", principal=" + (principal != null ? principal.principalId() : "none") + "]";
    }

    public static final class Builder implements RequestContext.Builder {
        private String tenantId = "default";
        private String correlationId = "";
        private PrincipalContext principal;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        @Override
        public Builder tenantId(String tenantId) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
            return this;
        }

        @Override
        public Builder correlationId(String correlationId) {
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
            return this;
        }

        @Override
        public Builder principal(PrincipalContext principal) {
            this.principal = principal;
            return this;
        }

        @Override
        public Builder attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (value == null) {
                this.attributes.remove(key);
            } else {
                this.attributes.put(key, value);
            }
            return this;
        }

        @Override
        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        @Override
        public RequestContext build() {
            String corrId = this.correlationId;
            if (corrId.isBlank()) {
                corrId = java.util.UUID.randomUUID().toString();
            }
            return new DefaultRequestContext(tenantId, corrId, principal, attributes);
        }
    }
}
