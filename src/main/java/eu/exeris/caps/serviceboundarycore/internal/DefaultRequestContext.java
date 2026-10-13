package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.ScopedValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Standard implementation of {@link RequestContext}, backed by {@link ScopedValue}.
 *
 * <p>The principal and the session id are set only through this package's {@code Builder} methods that
 * the public {@link RequestContext.Builder} does not have, and only {@link DefaultAdmissionContext} and
 * {@link DefaultApiSurfaceRegistry} call them. The public {@link #runWith} and {@link #callWith} refuse a
 * context that differs from the bound one in principal, tenant or session id.
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
    private final UUID sessionId;
    private final Map<String, Object> attributes;

    private DefaultRequestContext(String tenantId,
                                  String correlationId,
                                  PrincipalContext principal,
                                  UUID sessionId,
                                  Map<String, Object> attributes) {
        this.tenantId = tenantId;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.principal = principal;
        this.sessionId = sessionId;
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

    /**
     * Binds a context built by {@link RequestContext#builder()}; see {@link RequestContext#runWith}.
     *
     * @param context the context to bind
     * @param action  the action to run
     */
    public static void runWith(RequestContext context, Runnable action) {
        Objects.requireNonNull(action, "action");
        ScopedValue.where(CURRENT, guarded(context)).run(action);
    }

    /**
     * Binds a context built by {@link RequestContext#builder()}; see {@link RequestContext#callWith}.
     *
     * @param <R>     return type
     * @param <X>     exception type
     * @param context the context to bind
     * @param op      the operation to run
     * @return the operation's result
     * @throws X if the operation throws
     */
    public static <R, X extends Throwable> R callWith(RequestContext context,
                                                       ScopedValue.CallableOp<? extends R, X> op) throws X {
        Objects.requireNonNull(op, "op");
        return ScopedValue.where(CURRENT, guarded(context)).call(op);
    }

    /** Binds a context without the checks of the public entry points; reserved for admission. */
    static <R, X extends Throwable> R bind(RequestContext context,
                                           ScopedValue.CallableOp<? extends R, X> op) throws X {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(op, "op");
        return ScopedValue.where(CURRENT, context).call(op);
    }

    private static RequestContext guarded(RequestContext context) {
        Objects.requireNonNull(context, "context");
        if (!(context instanceof DefaultRequestContext candidate)) {
            throw new IllegalArgumentException("Only a context built by RequestContext.builder() can be bound");
        }
        if (CURRENT.isBound()) {
            RequestContext bound = CURRENT.get();
            if (!bound.principal().equals(candidate.principal())
                    || !bound.tenantId().equals(candidate.tenantId())
                    || !bound.sessionId().equals(candidate.sessionId())) {
                throw new IllegalStateException(
                        "A bound RequestContext cannot be replaced by one with another principal, tenant or session");
            }
        } else if (candidate.principal != null || candidate.sessionId != null) {
            throw new IllegalArgumentException("Only admission binds a context that carries a principal or a session");
        }
        return candidate;
    }

    @Override
    public Optional<String> tenantId() {
        return Optional.ofNullable(tenantId);
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
    public Optional<UUID> sessionId() {
        return Optional.ofNullable(sessionId);
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
        return Objects.equals(tenantId, that.tenantId) &&
                correlationId.equals(that.correlationId) &&
                Objects.equals(principal, that.principal) &&
                Objects.equals(sessionId, that.sessionId) &&
                attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, correlationId, principal, sessionId, attributes);
    }

    @Override
    public String toString() {
        return "RequestContext[tenantId=" + (tenantId != null ? tenantId : "none") + ", correlationId=" + correlationId +
                ", principal=" + (principal != null ? principal.principalId() : "none") + "]";
    }

    /** Builder of {@link DefaultRequestContext}; the principal and session id setters are package-private. */
    public static final class Builder implements RequestContext.Builder {
        private String tenantId;
        private String correlationId = "";
        private PrincipalContext principal;
        private UUID sessionId;
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

        Builder principal(PrincipalContext principal) {
            this.principal = principal;
            return this;
        }

        Builder sessionId(UUID sessionId) {
            this.sessionId = sessionId;
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
            return new DefaultRequestContext(tenantId, corrId, principal, sessionId, attributes);
        }
    }
}
