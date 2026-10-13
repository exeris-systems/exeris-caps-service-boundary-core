package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.caps.serviceboundarycore.internal.DefaultRequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.ScopedValue;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Request execution context carrying tenant identity, correlation tracking,
 * principal security context, and request-scoped attributes.
 *
 * <p>Backed by {@link ScopedValue} to guarantee safe, immutable context propagation across virtual
 * threads without thread-local leakage, satisfying ADR-012 tenant isolation requirements.
 *
 * <p>The principal, the tenant it carries and the session id are established only by admission: an
 * {@link AdmissionFilter} records them through {@link AdmissionContext}, and the registry binds the
 * resulting context for the handler. No method of this interface builds or binds a context that
 * carries a principal or a session id, and {@link #runWith} and {@link #callWith} refuse to replace
 * the principal, the tenant or the session id of a context that is already bound.
 *
 * @since 0.1.0
 */
public interface RequestContext {

    /**
     * Returns the tenant identifier for physical isolation (RLS), when one has been established.
     *
     * <p>The tenant comes from the host-bound context or from the tenant of the authenticated principal.
     * A request that has neither runs without a tenant: there is no implicit default tenant, so a
     * caller that needs one must refuse the request.
     *
     * @return the tenant identifier, or empty when none was established
     */
    Optional<String> tenantId();

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
     * Returns the identifier of the session the caller authenticated with, when admission recorded one.
     *
     * <p>This is the one place a session-bound capability reads the caller's session from. It is set
     * only through {@link AdmissionContext#authenticate(PrincipalContext, UUID)} and is empty on a
     * request whose principal was recorded without a session.
     *
     * @return the session identifier, or empty when the request carries none
     */
    Optional<UUID> sessionId();

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
     * <p>The context must come from {@link #builder()}. When a context is already bound, the new one
     * must carry the same principal, tenant and session id, so a handler cannot assume another identity.
     *
     * @param context the context to bind; never {@code null}
     * @param action  the runnable to execute; never {@code null}
     * @throws IllegalArgumentException if the context was not built by {@link #builder()} or carries a principal
     * @throws IllegalStateException    if a context is bound and the new one differs in principal, tenant or session id
     */
    static void runWith(RequestContext context, Runnable action) {
        DefaultRequestContext.runWith(context, action);
    }

    /**
     * Executes the given callable operation within the scope of the specified context.
     *
     * <p>The same rules as {@link #runWith(RequestContext, Runnable)} apply.
     *
     * @param <R>     return type
     * @param <X>     exception type
     * @param context the context to bind; never {@code null}
     * @param op      the operation to execute; never {@code null}
     * @return the result of the operation
     * @throws X if the operation throws
     * @throws IllegalArgumentException if the context was not built by {@link #builder()} or carries a principal
     * @throws IllegalStateException    if a context is bound and the new one differs in principal, tenant or session id
     */
    static <R, X extends Throwable> R callWith(RequestContext context,
                                               ScopedValue.CallableOp<? extends R, X> op) throws X {
        return DefaultRequestContext.callWith(context, op);
    }

    /**
     * Creates a new fluent builder for {@link RequestContext} instances that carry no principal.
     *
     * @return new builder
     */
    static Builder builder() {
        return new DefaultRequestContext.Builder();
    }

    /**
     * Fluent builder for a {@link RequestContext} without a principal or a session id.
     *
     * <p>A principal and a session id enter a context only through admission, so this builder has no
     * method for either.
     */
    interface Builder {

        /**
         * Sets the tenant identifier.
         *
         * @param tenantId tenant identifier; non-null
         * @return this builder
         */
        Builder tenantId(String tenantId);

        /**
         * Sets the correlation identifier; a blank one is replaced by a generated identifier at build time.
         *
         * @param correlationId correlation identifier; non-null
         * @return this builder
         */
        Builder correlationId(String correlationId);

        /**
         * Sets a request-scoped attribute; a {@code null} value removes it.
         *
         * @param key   attribute name; non-null
         * @param value attribute value, or {@code null} to remove it
         * @return this builder
         */
        Builder attribute(String key, Object value);

        /**
         * Adds every entry of the given map as a request-scoped attribute.
         *
         * @param attributes attributes to add; {@code null} adds nothing
         * @return this builder
         */
        Builder attributes(Map<String, Object> attributes);

        /**
         * Builds the context.
         *
         * @return a new immutable context without a principal
         */
        RequestContext build();
    }
}
