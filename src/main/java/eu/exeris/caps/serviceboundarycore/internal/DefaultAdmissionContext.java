package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard {@link AdmissionContext}: mutable while the admission filters run, sealed before the handler.
 *
 * <p>Filters of one request run sequentially on the request's thread, so the state needs no
 * synchronisation; it is bound through a {@link ScopedValue} and never escapes the request. The thread
 * that opens the context owns it, and every call from another thread is refused.
 *
 * @since 0.1.0
 */
public final class DefaultAdmissionContext implements AdmissionContext {

    private static final ScopedValue<DefaultAdmissionContext> CURRENT = ScopedValue.newInstance();

    private final RequestContext seed;
    private final Map<String, Object> attributes;
    private final Thread owner = Thread.currentThread();
    private PrincipalContext principal;
    private UUID sessionId;
    private String tenantId;
    private boolean sealed;

    private DefaultAdmissionContext(RequestContext seed) {
        this.seed = Objects.requireNonNull(seed, "seed");
        this.attributes = new LinkedHashMap<>(seed.attributes());
        this.principal = seed.principal().orElse(null);
        this.tenantId = seed.tenantId().orElse(null);
    }

    /**
     * Opens an admission context seeded from the {@link RequestContext} already bound by the host, if any.
     *
     * @return a new, unsealed admission context
     */
    public static DefaultAdmissionContext open() {
        RequestContext seed = RequestContext.isBound() ? RequestContext.current() : RequestContext.builder().build();
        return new DefaultAdmissionContext(seed);
    }

    public static AdmissionContext current() {
        return currentInternal();
    }

    static DefaultAdmissionContext currentInternal() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("No AdmissionContext is bound in the current scope");
        }
        return CURRENT.get();
    }

    public static boolean isBound() {
        return CURRENT.isBound();
    }

    static <R, X extends Throwable> R callWith(DefaultAdmissionContext context,
                                               ScopedValue.CallableOp<? extends R, X> op) throws X {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(op, "op");
        return ScopedValue.where(CURRENT, context).call(op);
    }

    @Override
    public void authenticate(PrincipalContext principal) {
        record(principal, null);
    }

    @Override
    public void authenticate(PrincipalContext principal, UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        record(principal, sessionId);
    }

    private void record(PrincipalContext principal, UUID sessionId) {
        Objects.requireNonNull(principal, "principal");
        requireOpen();
        if (this.principal != null) {
            throw new IllegalStateException("The request already has an authenticated principal");
        }
        this.principal = principal;
        this.sessionId = sessionId;
        principal.tenantId().ifPresent(tenant -> this.tenantId = tenant.toString());
    }

    @Override
    public void attribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public RequestContext requestContext() {
        requireOwner();
        DefaultRequestContext.Builder builder = new DefaultRequestContext.Builder();
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        return builder
                .correlationId(seed.correlationId())
                .principal(principal)
                .sessionId(sessionId)
                .attributes(attributes)
                .build();
    }

    /** Ends admission: after this, the context the handler runs in can no longer change. */
    void seal() {
        requireOwner();
        sealed = true;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("An admission context is confined to the thread that runs the request's filters");
        }
    }

    private void requireOpen() {
        requireOwner();
        if (sealed) {
            throw new IllegalStateException("Admission has ended for this request");
        }
    }
}
