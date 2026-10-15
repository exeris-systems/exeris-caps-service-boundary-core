package eu.exeris.caps.serviceboundarycore.api;

/**
 * Thrown when an authenticated principal's tenant differs from the host-bound tenant.
 *
 * <p>Its message is fixed and names neither tenant, so neither appears in a log line or a response built
 * from it.
 *
 * @since 0.1.0
 */
public class TenantConflictException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Tenant conflict: host tenant does not match principal tenant";

    public TenantConflictException() {
        super(DEFAULT_MESSAGE);
    }
}
