package eu.exeris.caps.serviceboundarycore.api;

import java.time.Duration;
import java.util.List;

/**
 * Service boundary lifecycle coordinator interface.
 *
 * <p>Allows boundary capabilities to register {@link BoundaryServiceHook} listeners
 * that are coordinated during the SKU runtime lifecycle.
 *
 * @since 0.1.0
 */
public interface ServiceLifecycleHooks {

    /**
     * Registers a lifecycle hook.
     *
     * @param hook the hook to register; never {@code null}
     */
    void registerHook(BoundaryServiceHook hook);

    /**
     * Unregisters a previously registered lifecycle hook.
     *
     * @param hook the hook to unregister; never {@code null}
     * @return {@code true} if the hook was removed, {@code false} otherwise
     */
    boolean unregisterHook(BoundaryServiceHook hook);

    /**
     * Returns an unmodifiable view of all registered hooks.
     *
     * @return list of registered hooks
     */
    List<BoundaryServiceHook> registeredHooks();

    /**
     * Drives the {@link BoundaryServiceHook#onInitialize()} phase across all hooks.
     *
     * @throws Exception if any hook throws during initialization
     */
    void onServiceInitialize() throws Exception;

    /**
     * Drives the {@link BoundaryServiceHook#onReady()} phase across all hooks.
     *
     * @throws Exception if any hook throws during readiness
     */
    void onServiceReady() throws Exception;

    /**
     * Drives the {@link BoundaryServiceHook#onDrain(Duration)} phase in reverse order.
     *
     * @param remaining remaining duration of the drain budget
     * @throws Exception if any hook throws during draining
     */
    void onServiceDrain(Duration remaining) throws Exception;

    /**
     * Drives the {@link BoundaryServiceHook#onTerminate()} phase in reverse order.
     * Never throws checked exceptions.
     */
    void onServiceTerminate();
}
