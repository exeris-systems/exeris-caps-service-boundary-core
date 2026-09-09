package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.BoundaryServiceHook;
import eu.exeris.caps.serviceboundarycore.api.ServiceLifecycleHooks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe composite implementation of {@link ServiceLifecycleHooks}.
 *
 * <p>Drives registered hooks in registration order during {@code initialize} and
 * {@code ready}, and in reverse order during {@code drain} and {@code terminate}.
 *
 * @since 0.1.0
 */
public final class CompositeServiceLifecycleHooks implements ServiceLifecycleHooks {

    private static final System.Logger LOG = System.getLogger(CompositeServiceLifecycleHooks.class.getName());

    private final List<BoundaryServiceHook> hooks = new CopyOnWriteArrayList<>();

    public CompositeServiceLifecycleHooks() {
    }

    @Override
    public void registerHook(BoundaryServiceHook hook) {
        Objects.requireNonNull(hook, "hook");
        hooks.add(hook);
        LOG.log(System.Logger.Level.DEBUG, "Registered service boundary lifecycle hook: {0}", hook);
    }

    @Override
    public boolean unregisterHook(BoundaryServiceHook hook) {
        Objects.requireNonNull(hook, "hook");
        return hooks.remove(hook);
    }

    @Override
    public List<BoundaryServiceHook> registeredHooks() {
        return Collections.unmodifiableList(new ArrayList<>(hooks));
    }

    @Override
    public void onServiceInitialize() throws Exception {
        LOG.log(System.Logger.Level.INFO, "Initializing {0} boundary service hook(s)", hooks.size());
        for (BoundaryServiceHook hook : hooks) {
            hook.onInitialize();
        }
    }

    @Override
    public void onServiceReady() throws Exception {
        LOG.log(System.Logger.Level.INFO, "Readying {0} boundary service hook(s)", hooks.size());
        for (BoundaryServiceHook hook : hooks) {
            hook.onReady();
        }
    }

    @Override
    public void onServiceDrain(Duration remaining) throws Exception {
        LOG.log(System.Logger.Level.INFO, "Draining {0} boundary service hook(s) with budget {1}",
                hooks.size(), remaining);
        // Reverse order for drain
        List<BoundaryServiceHook> reversed = new ArrayList<>(hooks);
        Collections.reverse(reversed);
        for (BoundaryServiceHook hook : reversed) {
            hook.onDrain(remaining);
        }
    }

    @Override
    public void onServiceTerminate() {
        LOG.log(System.Logger.Level.INFO, "Terminating {0} boundary service hook(s)", hooks.size());
        // Reverse order for terminate
        List<BoundaryServiceHook> reversed = new ArrayList<>(hooks);
        Collections.reverse(reversed);
        for (BoundaryServiceHook hook : reversed) {
            try {
                hook.onTerminate();
            } catch (Throwable t) {
                // Terminate must never throw; log and continue unwinding
                LOG.log(System.Logger.Level.WARNING, "Ignored exception during boundary hook terminate", t);
            }
        }
    }
}
