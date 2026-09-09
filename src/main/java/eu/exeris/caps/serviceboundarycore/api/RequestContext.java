package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.caps.serviceboundarycore.internal.DefaultRequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.ScopedValue;
import java.util.Map;
import java.util.Optional;

/**
 * Request execution context carrying tenant identity, correlation tracking,
 * principal security context, and request-scoped attributes.
 *
 * <p>Backed by {@link ScopedValue} to guarantee safe, allocation-free, and
 * immutable context propagation across virtual threads without thread-local
 * leakage, satisfying ADR-012 tenant isolation requirements.
 *
 * @since 0.1.0
 */
public interface RequestContext {

    /**
     * Returns the current tenant identifier for physical isolation (RLS).
     *
     * @return tenant identifier; never {@code null}
     */
    String tenantId();

    /**
     * Returns the correlation identifier for distributed tracing and logging.
     *
     * @return correlation ID; never {@code null}
     */
    String correlationId();

    /**
     * Returns the authenticated principal security context if present.
     *
     * @return optional principal context
     */
    Optional<PrincipalContext> principal();

    /**
     * Returns an unmodifiable map of request-scoped attributes.
     *
     * @return attributes map; never {@code null}
     */
    Map<String, Object> attributes();

    /**
     * Look up a request-scoped attribute by name.
     *
     * @param key attribute name
     * @return optional attribute value
     */
    Optional<Object> attribute(String key);

    /**
     * Returns the current thread's bound {@link RequestContext}.
     *
     * @return current request context
     * @throws IllegalStateException if no context is bound in the current scope
     */
    static RequestContext current() {
        return DefaultRequestContext.current();
    }

    /**
     * Checks if a {@link RequestContext} is bound in the current scope.
     *
     * @return true if a context is bound, false otherwise
     */
    static boolean isBound() {
        return DefaultRequestContext.isBound();
    }

    /**
     * Executes the given runnable within the scope of the specified context.
     *
     * @param context the context to bind; never {@code null}
     * @param action  the runnable to execute; never {@code null}
     */
    static void runWith(RequestContext context, Runnable action) {
        DefaultRequestContext.runWith(context, action);
    }

    /**
     * Executes the given callable operation within the scope of the specified context.
     *
     * @param <R>     return type
     * @param <X>     exception type
     * @param context the context to bind; never {@code null}
     * @param op      the operation to execute; never {@code null}
     * @return the result of the operation
     * @throws X if the operation throws
     */
    static <R, X extends Throwable> R callWith(RequestContext context,
                                               ScopedValue.CallableOp<? extends R, X> op) throws X {
        return DefaultRequestContext.callWith(context, op);
    }

    /**
     * Creates a new fluent builder for constructing {@link RequestContext} instances.
     *
     * @return new builder
     */
    static Builder builder() {
        return new DefaultRequestContext.Builder();
    }

    /**
     * Fluent builder for {@link RequestContext}.
     */
    interface Builder {
        Builder tenantId(String tenantId);
        Builder correlationId(String correlationId);
        Builder principal(PrincipalContext principal);
        Builder attribute(String key, Object value);
        Builder attributes(Map<String, Object> attributes);
        RequestContext build();
    }
}
