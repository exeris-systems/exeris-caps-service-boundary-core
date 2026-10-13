package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
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
}
