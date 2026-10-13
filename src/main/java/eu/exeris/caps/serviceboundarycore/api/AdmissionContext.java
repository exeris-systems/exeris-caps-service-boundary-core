package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.caps.serviceboundarycore.internal.DefaultAdmissionContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

/**
 * The per-request state an {@link AdmissionFilter} contributes to the {@link RequestContext} the route
 * handler runs in (ADR-099).
 *
 * <p>{@link ApiSurfaceRegistry} opens one admission context per matched request and binds it, together
 * with the {@link RequestContext} established so far, while each filter runs. After the last filter
 * passes, the registry seals it and runs the handler inside the resulting {@link RequestContext}. A
 * handler therefore never sees an admission context: the principal it reads from
 * {@link RequestContext#principal()} is the one an admission filter established, and nothing on the
 * handler's side can replace it.
 *
 * @apiNote An admission context is confined to the thread that runs the request's filters. It is
 *     bound for the duration of that run only; it must not be stored or handed to another thread.
 * @since 0.1.0
 */
public interface AdmissionContext {

    /**
     * Records the authenticated principal of this request, and its tenant when the principal carries one.
     *
     * <p>A request has at most one principal: a second call is refused, so a later filter cannot
     * silently replace the identity an earlier one verified.
     *
     * @param principal verified principal; non-null
     * @throws IllegalStateException if a principal is already established or the context is sealed
     */
    void authenticate(PrincipalContext principal);

    /**
     * Adds a request-scoped attribute that the handler reads through {@link RequestContext#attribute(String)}.
     *
     * @param key attribute name; non-null
     * @param value attribute value; {@code null} removes the attribute
     * @throws IllegalStateException if the context is sealed
     */
    void attribute(String key, Object value);

    /**
     * Returns the {@link RequestContext} as established by the filters that have run so far.
     *
     * @return current request context snapshot; never {@code null}
     */
    RequestContext requestContext();

    /**
     * Returns the admission context bound to the running admission filter.
     *
     * @return the bound admission context
     * @throws IllegalStateException if no admission filter is running in the current scope
     */
    static AdmissionContext current() {
        return DefaultAdmissionContext.current();
    }

    /**
     * Checks whether an admission context is bound in the current scope.
     *
     * @return {@code true} while an admission filter runs under {@link ApiSurfaceRegistry}
     */
    static boolean isBound() {
        return DefaultAdmissionContext.isBound();
    }
}
