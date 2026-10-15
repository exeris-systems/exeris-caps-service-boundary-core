package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ServiceBoundaryCoreModule")
class ServiceBoundaryCoreModuleTest {

    @Test
    @DisplayName("instantiates via public no-arg constructor and exposes non-null registries")
    void defaultConstructor() {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        assertThat(module.apiSurfaceRegistry()).isNotNull();
        assertThat(module.serviceLifecycleHooks()).isNotNull();
        assertThat(module.requestFilterChain()).isNotNull();
    }

    @Test
    @DisplayName("coordinates lifecycle phases and invokes hooks in proper order")
    void lifecycleCoordination() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        List<String> events = new ArrayList<>();

        BoundaryServiceHook hook1 = new BoundaryServiceHook() {
            @Override
            public void onInitialize() {
                events.add("hook1:init");
            }

            @Override
            public void onReady() {
                events.add("hook1:ready");
            }

            @Override
            public void onDrain(Duration remaining) {
                events.add("hook1:drain:" + Math.round(remaining.toMillis() / 1000.0));
            }

            @Override
            public void onTerminate() {
                events.add("hook1:term");
            }
        };

        BoundaryServiceHook hook2 = new BoundaryServiceHook() {
            @Override
            public void onInitialize() {
                events.add("hook2:init");
            }

            @Override
            public void onReady() {
                events.add("hook2:ready");
            }

            @Override
            public void onDrain(Duration remaining) {
                events.add("hook2:drain:" + Math.round(remaining.toMillis() / 1000.0));
            }

            @Override
            public void onTerminate() {
                events.add("hook2:term");
            }
        };

        module.serviceLifecycleHooks().registerHook(hook1);
        module.serviceLifecycleHooks().registerHook(hook2);

        assertThat(module.serviceLifecycleHooks().registeredHooks()).containsExactly(hook1, hook2);

        // Forward order for init and ready
        module.initialize();
        assertThat(events).containsExactly("hook1:init", "hook2:init");

        module.ready();
        assertThat(events).containsExactly("hook1:init", "hook2:init", "hook1:ready", "hook2:ready");

        // Reverse order for drain and terminate
        module.drain(Duration.ofSeconds(15));
        assertThat(events).containsExactly(
                "hook1:init", "hook2:init",
                "hook1:ready", "hook2:ready",
                "hook2:drain:15", "hook1:drain:15"
        );

        module.terminate();
        assertThat(events).containsExactly(
                "hook1:init", "hook2:init",
                "hook1:ready", "hook2:ready",
                "hook2:drain:15", "hook1:drain:15",
                "hook2:term", "hook1:term"
        );
    }

    @Test
    @DisplayName("throwing initialize propagates to abort boot")
    void initializeFailureAborts() {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();

        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override
            public void onInitialize() throws Exception {
                throw new IllegalStateException("Subsystem database unavailable");
            }
        });

        assertThatThrownBy(module::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Subsystem database unavailable");
    }

    @Test
    @DisplayName("throwing terminate does not halt remaining hooks")
    void terminateFaultTolerance() {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        List<String> terminated = new ArrayList<>();

        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override
            public void onTerminate() {
                terminated.add("h1");
            }
        });
        module.serviceLifecycleHooks().registerHook(new BoundaryServiceHook() {
            @Override
            public void onTerminate() {
                throw new RuntimeException("Simulated error in hook 2 terminate");
            }
        });

        module.terminate();
        // Since terminate is reverse order, hook 2 ran (threw, caught/logged), then hook 1 ran
        assertThat(terminated).containsExactly("h1");
    }

    @Test
    @DisplayName("unregisters hooks successfully")
    void unregistersHook() {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        BoundaryServiceHook hook = new BoundaryServiceHook() {};

        module.serviceLifecycleHooks().registerHook(hook);
        assertThat(module.serviceLifecycleHooks().registeredHooks()).hasSize(1);

        boolean removed = module.serviceLifecycleHooks().unregisterHook(hook);
        assertThat(removed).isTrue();
        assertThat(module.serviceLifecycleHooks().registeredHooks()).isEmpty();
    }

    @Test
    @DisplayName("registry mutation after ready throws for each mutator")
    void registryMutationAfterReadyThrows() throws Exception {
        ServiceBoundaryCoreModule module = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry registry = module.apiSurfaceRegistry();

        ApiSurfaceRoute existingRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/existing")
                .actionName("Exist#get")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build();
        registry.registerRoute(existingRoute);

        AdmissionFilter existingFilter = new AdmissionFilter() {
            @Override public String name() { return "existing-filter"; }
            @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
            @Override public boolean filter(HttpExchange ex, RouteMatch rm) { return true; }
        };
        registry.registerFilter(existingFilter);

        module.ready();

        ApiSurfaceRoute newRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/new")
                .actionName("New#get")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build();

        AdmissionFilter newFilter = new AdmissionFilter() {
            @Override public String name() { return "new-filter"; }
            @Override public AdmissionPhase phase() { return AdmissionPhase.PRE_ADMISSION; }
            @Override public boolean filter(HttpExchange ex, RouteMatch rm) { return true; }
        };

        // 1. registerRoute throws
        assertThatThrownBy(() -> registry.registerRoute(newRoute))
                .isInstanceOf(IllegalStateException.class);

        // 2. unregisterRoute throws
        assertThatThrownBy(() -> registry.unregisterRoute(existingRoute))
                .isInstanceOf(IllegalStateException.class);

        // 3. registerFilter throws
        assertThatThrownBy(() -> registry.registerFilter(newFilter))
                .isInstanceOf(IllegalStateException.class);

        // 4. unregisterFilter throws
        assertThatThrownBy(() -> registry.unregisterFilter(existingFilter))
                .isInstanceOf(IllegalStateException.class);

        // 5. clear throws
        assertThatThrownBy(registry::clear)
                .isInstanceOf(IllegalStateException.class);

        // Reads continue to work
        assertThat(registry.routeCount()).isEqualTo(1);
        assertThat(registry.routes()).containsExactly(existingRoute);
        assertThat(registry.filters()).containsExactly(existingFilter);
        assertThat(registry.matchRoute(HttpMethod.GET, "/existing")).isPresent();
    }

    @Test
    @DisplayName("strict mode refuses an empty-requirement route and accepts permitAll")
    void strictModeRouteRequirements() {
        ServiceBoundaryCoreModule strictModule = ServiceBoundaryCoreModule.builder()
                .strictRoutes(true)
                .build();
        assertThat(strictModule.isStrictRoutes()).isTrue();
        ApiSurfaceRegistry strictRegistry = strictModule.apiSurfaceRegistry();

        ApiSurfaceRoute emptyReqRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/empty-req")
                .actionName("Empty#get")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build();

        // Strict mode refuses empty requirements
        assertThatThrownBy(() -> strictRegistry.registerRoute(emptyReqRoute))
                .isInstanceOf(IllegalStateException.class);

        // Strict mode accepts permitAll via kernel RouteRequirement.permitAll()
        ApiSurfaceRoute kernelPermitAllRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/kernel-permit-all")
                .actionName("KernelPermit#get")
                .requirements(List.of(RouteRequirement.permitAll()))
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build();
        strictRegistry.registerRoute(kernelPermitAllRoute);
        assertThat(strictRegistry.routeCount()).isEqualTo(1);

        // Non-strict mode accepts empty requirements
        ServiceBoundaryCoreModule nonStrictModule = new ServiceBoundaryCoreModule();
        assertThat(nonStrictModule.isStrictRoutes()).isFalse();
        nonStrictModule.apiSurfaceRegistry().registerRoute(emptyReqRoute);
        assertThat(nonStrictModule.apiSurfaceRegistry().routeCount()).isEqualTo(1);

        // Builder refuses custom registry with strictRoutes
        assertThatThrownBy(() -> ServiceBoundaryCoreModule.builder()
                .registry(strictRegistry)
                .strictRoutes(true)
                .build())
                .isInstanceOf(IllegalStateException.class);
    }
}
