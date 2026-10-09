package eu.exeris.caps.serviceboundarycore.api;

/**
 * Deterministic admission phases for request filtering and policy evaluation (ADR-024, ADR-099).
 *
 * <p>Enforces strict execution ordering:
 * <ol>
 *   <li>{@link #PRE_ADMISSION}: Protocol and envelope pre-checks</li>
 *   <li>{@link #RATE_LIMITING}: Throttling and resource protection before expensive crypto</li>
 *   <li>{@link #AUTHENTICATION}: Token verification and principal extraction (e.g. Ed25519 EdDSA)</li>
 *   <li>{@link #AUTHORIZATION}: Tenant/role checks and permission evaluation</li>
 *   <li>{@link #POST_ADMISSION}: Auditing, metrics, and pre-handler context finalization</li>
 * </ol>
 *
 * @since 0.1.0
 */
public enum AdmissionPhase {

    /**
     * Protocol and envelope validation before rate limiting or credentials inspection.
     */
    PRE_ADMISSION,

    /**
     * Throttling, token buckets, and brute-force protection before executing cryptographic routines.
     */
    RATE_LIMITING,

    /**
     * Identity validation, token/signature verification, and principal context establishment.
     */
    AUTHENTICATION,

    /**
     * RBAC policies, permissions, and tenant access verification.
     */
    AUTHORIZATION,

    /**
     * Post-admission interception, request tracing, and pre-dispatch finalization.
     */
    POST_ADMISSION
}
