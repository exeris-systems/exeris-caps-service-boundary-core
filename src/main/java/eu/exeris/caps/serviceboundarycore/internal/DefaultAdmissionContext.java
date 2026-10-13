package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Standard {@link AdmissionContext}: mutable while the admission filters run, sealed before the handler.
 *
 * <p>Filters of one request run sequentially on the request's thread, so the state needs no
 * synchronisation; it is bound through a {@link ScopedValue} and never escapes the request.
 *
 * @since 0.1.0
 */
public final class DefaultAdmissionContext implements AdmissionContext {

    private static final ScopedValue<DefaultAdmissionContext> CURRENT = ScopedValue.newInstance();

    private final RequestContext seed;
    private final Map<String, Object> attributes;
    private PrincipalContext principal;
    private String tenantId;
    private boolean sealed;

    private DefaultAdmissionContext(RequestContext seed) {
        this.seed = Objects.requireNonNull(seed, "seed");
        this.attributes = new LinkedHashMap<>(seed.attributes());
        this.principal = seed.principal().orElse(null);
        this.tenantId = seed.tenantId();
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
        Objects.requireNonNull(principal, "principal");
        requireOpen();
        if (this.principal != null) {
            throw new IllegalStateException("The request already has an authenticated principal");
        }
        this.principal = principal;
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
        return RequestContext.builder()
                .tenantId(tenantId)
                .correlationId(seed.correlationId())
                .principal(principal)
                .attributes(attributes)
                .build();
    }

    /** Ends admission: after this, the context the handler runs in can no longer change. */
    void seal() {
        sealed = true;
    }

    private void requireOpen() {
        if (sealed) {
            throw new IllegalStateException("Admission has ended for this request");
        }
    }
}
