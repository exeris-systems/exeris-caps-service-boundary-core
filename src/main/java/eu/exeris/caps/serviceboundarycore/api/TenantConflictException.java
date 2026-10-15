package eu.exeris.caps.serviceboundarycore.api;

/**
 * Thrown when an authenticated principal's tenant differs from the host-bound tenant.
 *
 * <p>The exception carries a fixed message that names neither tenant to prevent information disclosure.
 *
 * @since 0.1.0
 */
public class TenantConflictException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Tenant conflict: host tenant does not match principal tenant";

    public TenantConflictException() {
        super(DEFAULT_MESSAGE);
    }

    public TenantConflictException(String message) {
        super(message != null ? message : DEFAULT_MESSAGE);
    }
}
