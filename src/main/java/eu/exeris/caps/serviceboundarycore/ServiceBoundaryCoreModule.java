package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.ServiceLifecycleHooks;
import eu.exeris.caps.serviceboundarycore.internal.CompositeServiceLifecycleHooks;
import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;
import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

import java.time.Duration;
import java.util.Objects;

/**
 * The capability declaration and lifecycle owner for {@code exeris-caps-service-boundary-core}.
 *
 * <p>Provides the substrate foundation for all Service Boundary capabilities and SKUs:
 * <ul>
 *   <li>{@link ApiSurfaceRegistry} for build-time emitted route and action dispatch;</li>
 *   <li>{@link ServiceLifecycleHooks} for lifecycle interceptors and hooks;</li>
 *   <li>{@link RequestContext} for virtual-thread context and physical tenant isolation (ADR-012);</li>
 *   <li>{@link RequestFilterChain} for ordered request admission filtering (ADR-099).</li>
 * </ul>
 *
 * <p>Annotated with {@code @CapabilityLifecycle} and implements {@link CapabilityLifecycleHooks}
 * to coordinate the boundary lifecycle with the composition runtime.
 *
 * <p>{@link #ready()} freezes the default registry; {@link #beginTeardown()} (also entered by
 * {@link #drain(Duration)} and {@link #terminate()}) ends serving and lets capabilities detach.
 *
 * @since 0.1.0
 */
@CapabilityModule
@CapabilityLifecycle
@Provides(service = ApiSurfaceRegistry.class, version = "0.1.0")
@Provides(service = ServiceLifecycleHooks.class, version = "0.1.0")
@Provides(service = RequestContext.class, version = "0.1.0")
@Provides(service = RequestFilterChain.class, version = "0.1.0")
public final class ServiceBoundaryCoreModule implements CapabilityLifecycleHooks {

    private final ApiSurfaceRegistry registry;
    private final ServiceLifecycleHooks lifecycleHooks;

    /**
     * Public no-arg constructor for reflective instantiation by the composition runtime. The default
     * registry is strict: a route that declares no requirement is refused at registration.
     */
    public ServiceBoundaryCoreModule() {
        this(new DefaultApiSurfaceRegistry(), new CompositeServiceLifecycleHooks());
    }

    /**
     * Creates the module around the given registry and lifecycle hooks.
     *
     * @param registry       the route and admission registry; non-null
     * @param lifecycleHooks the boundary lifecycle hooks; non-null
     */
    public ServiceBoundaryCoreModule(ApiSurfaceRegistry registry, ServiceLifecycleHooks lifecycleHooks) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycleHooks = Objects.requireNonNull(lifecycleHooks, "lifecycleHooks");
    }

    /**
     * Returns a fluent builder for configuring a {@link ServiceBoundaryCoreModule}.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reports whether the registry this module provides refuses, at registration, a route that declares no
     * requirement. The answer is read from the registry, whichever way the module was built:
     * <ul>
     *   <li>{@code new ServiceBoundaryCoreModule()} and a builder that sets neither a registry nor
     *       {@code strictRoutes}: {@code true};</li>
     *   <li>a builder with {@code strictRoutes(false)} and no registry: {@code false};</li>
     *   <li>a registry passed to the builder or to the two-argument constructor: {@code true} when it is a
     *       {@link DefaultApiSurfaceRegistry} created strict, {@code false} when it was created lenient, and
     *       {@code false} for any other implementation, whose route policy is not known here. A builder given
     *       a registry ignores {@code strictRoutes(false)}, and refuses {@code strictRoutes(true)}.</li>
     * </ul>
     *
     * @return whether the registry in use refuses a route without a requirement, as described above
     */
    public boolean isStrictRoutes() {
        return registry instanceof DefaultApiSurfaceRegistry r && r.isStrictRoutes();
    }

    /** Returns the route and admission registry this module provides. */
    public ApiSurfaceRegistry apiSurfaceRegistry() {
        return registry;
    }

    /** Returns the request filter chain of the registry. */
    public RequestFilterChain requestFilterChain() {
        return registry.filterChain();
    }

    /** Returns the boundary lifecycle hooks this module provides. */
    public ServiceLifecycleHooks serviceLifecycleHooks() {
        return lifecycleHooks;
    }

    @Override
    public void initialize() throws Exception {
        lifecycleHooks.onServiceInitialize();
    }

    @Override
    public void ready() throws Exception {
        if (registry instanceof DefaultApiSurfaceRegistry r) {
            r.freeze();
        }
        lifecycleHooks.onServiceReady();
    }

    /**
     * Ends serving: from now on the default registry answers every request 503, refuses every addition and
     * permits removal, so capabilities that attached a filter or route can detach. One-way, idempotent, never
     * throws; a registry supplied to the constructor is responsible for its own phases and is not touched.
     *
     * <p>{@link #drain(Duration)} and {@link #terminate()} call it first. A composition that terminates
     * capabilities before this module has drained or terminated calls it before terminating any of them.
     */
    public void beginTeardown() {
        if (registry instanceof DefaultApiSurfaceRegistry r) {
            r.beginTeardown();
        }
    }

    @Override
    public void drain(Duration remaining) throws Exception {
        beginTeardown();
        lifecycleHooks.onServiceDrain(remaining);
    }

    @Override
    public void terminate() {
        beginTeardown();
        lifecycleHooks.onServiceTerminate();
    }

    /** Fluent builder for {@link ServiceBoundaryCoreModule}. */
    public static final class Builder {
        private ApiSurfaceRegistry registry;
        private ServiceLifecycleHooks lifecycleHooks;
        private Boolean strictRoutes;

        public Builder registry(ApiSurfaceRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder lifecycleHooks(ServiceLifecycleHooks lifecycleHooks) {
            this.lifecycleHooks = lifecycleHooks;
            return this;
        }

        /**
         * Sets whether the default registry refuses, at registration, a route that declares no requirement.
         * Strict is the default: a module built without this call is strict, and a route is declared
         * anonymous with {@code RouteRequirement.permitAll()}.
         *
         * <p>{@code strictRoutes(false)} lets the registry accept a route with no requirement, or with a
         * {@code null} list, and serve it to a request that no filter authenticated. It exists for
         * compositions that have not declared their routes yet; a route registered that way is open to every
         * caller until a requirement is declared.
         *
         * <p>Strict mode is a property of the default registry: setting it to {@code true} together with a
         * custom {@link #registry(ApiSurfaceRegistry)} is refused by {@link #build()}.
         *
         * @param strictRoutes {@code true} to refuse routes without a requirement, {@code false} to accept them
         * @return this builder
         */
        public Builder strictRoutes(boolean strictRoutes) {
            this.strictRoutes = strictRoutes;
            return this;
        }

        public ServiceBoundaryCoreModule build() {
            if (Boolean.TRUE.equals(strictRoutes) && this.registry != null) {
                throw new IllegalStateException("Strict route mode is a property of the default registry and cannot be combined with a custom registry");
            }
            ApiSurfaceRegistry reg = this.registry != null
                    ? this.registry
                    : new DefaultApiSurfaceRegistry(strictRoutes == null || strictRoutes);
            ServiceLifecycleHooks hooks = this.lifecycleHooks != null
                    ? this.lifecycleHooks
                    : new CompositeServiceLifecycleHooks();
            return new ServiceBoundaryCoreModule(reg, hooks);
        }
    }
}
