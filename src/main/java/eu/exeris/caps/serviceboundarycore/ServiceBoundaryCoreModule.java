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
 * @since 0.1.0
 */
@CapabilityModule
@CapabilityLifecycle
@Provides(service = ApiSurfaceRegistry.class, version = "1.1.0")
@Provides(service = ServiceLifecycleHooks.class, version = "1.0.0")
@Provides(service = RequestContext.class, version = "1.0.0")
@Provides(service = RequestFilterChain.class, version = "1.1.0")
public final class ServiceBoundaryCoreModule implements CapabilityLifecycleHooks {

    private final ApiSurfaceRegistry registry;
    private final ServiceLifecycleHooks lifecycleHooks;

    /**
     * Public no-arg constructor for reflective instantiation by the composition runtime.
     */
    public ServiceBoundaryCoreModule() {
        this(new DefaultApiSurfaceRegistry(), new CompositeServiceLifecycleHooks());
    }

    public ServiceBoundaryCoreModule(ApiSurfaceRegistry registry, ServiceLifecycleHooks lifecycleHooks) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycleHooks = Objects.requireNonNull(lifecycleHooks, "lifecycleHooks");
    }

    public ApiSurfaceRegistry apiSurfaceRegistry() {
        return registry;
    }

    public RequestFilterChain requestFilterChain() {
        return registry.filterChain();
    }

    public ServiceLifecycleHooks serviceLifecycleHooks() {
        return lifecycleHooks;
    }

    @Override
    public void initialize() throws Exception {
        lifecycleHooks.onServiceInitialize();
    }

    @Override
    public void ready() throws Exception {
        lifecycleHooks.onServiceReady();
    }

    @Override
    public void drain(Duration remaining) throws Exception {
        lifecycleHooks.onServiceDrain(remaining);
    }

    @Override
    public void terminate() {
        lifecycleHooks.onServiceTerminate();
    }
}
