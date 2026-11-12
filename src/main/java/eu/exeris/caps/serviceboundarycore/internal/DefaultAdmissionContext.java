package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
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
 * <p>{@link #requestContext()} returns the same instance until a write changes the principal, session, tenant
 * or attributes, and a new one after it. Contexts are immutable, so an instance handed to a filter keeps the
 * state it was built from.
 *
 * <p>The attribute map is allocated by the first write that changes it; a request whose filters set no
 * attribute allocates none, and reading an empty one allocates nothing.
 *
 * @since 0.1.0
 */
public final class DefaultAdmissionContext implements AdmissionContext {

    private static final ScopedValue<DefaultAdmissionContext> CURRENT = ScopedValue.newInstance();

    private final RequestContext seed;
    /**
     * The attributes admission has established. Until the first write it is the seed's immutable map, which is
     * shared and never modified; the first write that changes it replaces it with a private mutable copy.
     */
    private Map<String, Object> attributes;
    private boolean attributesOwned;
    /**
     * The context built from the state as it stands, or {@code null} when that state changed since the last
     * build. Every write to the principal, session, tenant or attributes clears it, so a context handed out
     * reflects exactly the state established when it was requested and never a later one. Read and written
     * only after {@link #requireOwner()}, hence only by the thread that runs the request's filters.
     */
    private RequestContext established;
    private final Thread owner = Thread.currentThread();
    private PrincipalContext principal;
    private UUID sessionId;
    private String tenantId;
    private boolean sealed;

    private DefaultAdmissionContext(RequestContext seed) {
        this.seed = Objects.requireNonNull(seed, "seed");
        this.attributes = Map.copyOf(seed.attributes());
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
        if (this.tenantId != null && principal.tenantId().isPresent()) {
            String principalTenant = principal.tenantId().get().toString();
            if (!this.tenantId.equalsIgnoreCase(principalTenant)) {
                throw new TenantConflictException();
            }
        }
        try {
            this.principal = principal;
            this.sessionId = sessionId;
            principal.tenantId().ifPresent(tenant -> this.tenantId = tenant.toString());
        } finally {
            established = null;
        }
    }

    @Override
    public void attribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        if (value == null) {
            if (!attributes.containsKey(key)) {
                return;
            }
            established = null;
            ownAttributes();
            attributes.remove(key);
        } else {
            established = null;
            ownAttributes();
            attributes.put(key, value);
        }
    }

    /** Replaces the shared seed attributes with a private mutable copy that keeps their iteration order. */
    private void ownAttributes() {
        if (!attributesOwned) {
            attributes = new LinkedHashMap<>(attributes);
            attributesOwned = true;
        }
    }

    @Override
    public RequestContext requestContext() {
        requireOwner();
        RequestContext built = established;
        if (built == null) {
            built = DefaultRequestContext.of(tenantId, seed.correlationId(), principal, sessionId, attributes);
            established = built;
        }
        return built;
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
