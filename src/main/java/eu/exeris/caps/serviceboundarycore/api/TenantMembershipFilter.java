package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service Boundary admission filter that verifies the authenticated principal belongs to the tenant
 * designated by a path parameter.
 *
 * <p>Executes in {@link AdmissionPhase#AUTHORIZATION}.
 *
 * @since 0.1.0
 */
public final class TenantMembershipFilter implements AdmissionFilter {

    /** Filter name in the admission chain. */
    public static final String NAME = "tenant-membership";

    private final String parameterName;
    private final Set<String> bypassingRoles;

    private TenantMembershipFilter(String parameterName, Set<String> bypassingRoles) {
        Objects.requireNonNull(parameterName, "parameterName");
        String trimmed = parameterName.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("parameterName cannot be blank");
        }
        this.parameterName = trimmed;
        this.bypassingRoles = bypassingRoles != null ? Set.copyOf(bypassingRoles) : Set.of();
    }

    /**
     * Creates a tenant membership filter verifying the tenant in the given path parameter.
     *
     * @param parameterName name of the path parameter, e.g. "orgId"
     * @return a new filter instance
     */
    public static TenantMembershipFilter pathParameter(String parameterName) {
        return new TenantMembershipFilter(parameterName, Set.of());
    }

    /**
     * Returns a new filter instance that admits any principal holding one of the specified bypass roles.
     *
     * @param roles set of role names that bypass tenant check
     * @return new filter instance with bypass roles configured
     */
    public TenantMembershipFilter bypassingRoles(Set<String> roles) {
        return new TenantMembershipFilter(this.parameterName, roles);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AdmissionPhase phase() {
        return AdmissionPhase.AUTHORIZATION;
    }

    @Override
    public boolean filter(HttpExchange exchange, RouteMatch match) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(match, "match");

        if (!match.route().paramNames().contains(parameterName)) {
            return true;
        }

        Optional<PrincipalContext> principalOpt = RequestContext.isBound()
                ? RequestContext.current().principal()
                : Optional.empty();

        if (principalOpt.isEmpty()) {
            exchange.respond(HttpStatus.UNAUTHORIZED);
            return false;
        }

        PrincipalContext principal = principalOpt.get();
        for (String role : bypassingRoles) {
            if (principal.hasRole(role)) {
                return true;
            }
        }

        Optional<UUID> tenantOpt = principal.tenantId();
        if (tenantOpt.isEmpty()) {
            exchange.respond(HttpStatus.FORBIDDEN);
            return false;
        }

        String pathValue = match.pathParams().get(parameterName);
        if (pathValue == null) {
            exchange.respond(HttpStatus.FORBIDDEN);
            return false;
        }

        try {
            if (!UUID.fromString(pathValue).toString().equals(pathValue)
                    || !tenantOpt.get().toString().equals(pathValue)) {
                exchange.respond(HttpStatus.FORBIDDEN);
                return false;
            }
        } catch (IllegalArgumentException ex) {
            exchange.respond(HttpStatus.FORBIDDEN);
            return false;
        }

        return true;
    }
}
