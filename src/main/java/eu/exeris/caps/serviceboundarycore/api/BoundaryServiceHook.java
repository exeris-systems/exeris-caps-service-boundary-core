package eu.exeris.caps.serviceboundarycore.api;

import java.time.Duration;

/**
 * Lifecycle hook for boundary services and domain capabilities participating in the
 * Service Boundary lifecycle.
 *
 * <p>Registered with {@link ServiceLifecycleHooks}, these callbacks are invoked in
 * order during initialization and readiness, and in reverse order during drain and
 * termination.
 *
 * @since 0.1.0
 */
public interface BoundaryServiceHook {

    /**
     * Invoked during capability initialization after the kernel reports ready.
     *
     * @throws Exception if initialization fails, causing boot unwind
     */
    default void onInitialize() throws Exception {}

    /**
     * Invoked when all capabilities in the composition have completed initialization.
     *
     * @throws Exception if post-initialization readiness checks fail
     */
    default void onReady() throws Exception {}

    /**
     * Invoked during graceful shutdown within the remaining drain budget.
     *
     * @param remaining what is left of the drain budget when this hook is invoked
     * @throws Exception if draining fails
     */
    default void onDrain(Duration remaining) throws Exception {}

    /**
     * Invoked during termination to release resources. Must not throw exceptions.
     */
    default void onTerminate() {}
}
