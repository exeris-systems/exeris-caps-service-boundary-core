package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompositeServiceLifecycleHooks drain")
class CompositeServiceLifecycleHooksTest {

    private static BoundaryServiceHook hook(List<String> log, String name, Duration[] seen, int slot, boolean fail, long sleepMillis) {
        return new BoundaryServiceHook() {
            @Override
            public void onDrain(Duration remaining) throws Exception {
                log.add(name);
                seen[slot] = remaining;
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                if (fail) {
                    throw new IllegalStateException(name + " failed");
                }
            }
        };
    }

    @Test
    @DisplayName("a failing hook does not stop the others, and the first failure is rethrown")
    void failingHookDoesNotStopDrain() {
        CompositeServiceLifecycleHooks hooks = new CompositeServiceLifecycleHooks();
        List<String> log = new ArrayList<>();
        Duration[] seen = new Duration[3];
        hooks.registerHook(hook(log, "first", seen, 0, false, 0));
        hooks.registerHook(hook(log, "second", seen, 1, true, 0));
        hooks.registerHook(hook(log, "third", seen, 2, true, 0));

        assertThatThrownBy(() -> hooks.onServiceDrain(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("third failed")
                .hasSuppressedException(new IllegalStateException("second failed"));
        assertThat(log).containsExactly("third", "second", "first");
    }

    @Test
    @DisplayName("hooks that throw one shared exception instance all run and it is rethrown once")
    void sharedExceptionInstance() {
        CompositeServiceLifecycleHooks hooks = new CompositeServiceLifecycleHooks();
        List<String> log = new ArrayList<>();
        IllegalStateException shared = new IllegalStateException("shared");
        for (String name : List.of("a", "b", "c")) {
            hooks.registerHook(new BoundaryServiceHook() {
                @Override
                public void onDrain(Duration remaining) {
                    log.add(name);
                    throw shared;
                }
            });
        }

        assertThatThrownBy(() -> hooks.onServiceDrain(Duration.ofSeconds(5)))
                .isSameAs(shared);
        assertThat(log).containsExactly("c", "b", "a");
        assertThat(shared.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("each hook receives what is left of the budget")
    void budgetShrinks() throws Exception {
        CompositeServiceLifecycleHooks hooks = new CompositeServiceLifecycleHooks();
        List<String> log = new ArrayList<>();
        Duration[] seen = new Duration[2];
        hooks.registerHook(hook(log, "last-drained", seen, 0, false, 0));
        hooks.registerHook(hook(log, "first-drained", seen, 1, false, 200));

        hooks.onServiceDrain(Duration.ofSeconds(5));

        assertThat(seen[1]).isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(seen[0]).isLessThanOrEqualTo(Duration.ofSeconds(5).minusMillis(200));
    }
}
