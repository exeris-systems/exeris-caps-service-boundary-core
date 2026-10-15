package eu.exeris.caps.serviceboundarycore.api;

import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.security.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantMembershipFilter")
class TenantMembershipFilterTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private DefaultApiSurfaceRegistry registry;
    private AtomicReference<PrincipalContext> currentPrincipal;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        currentPrincipal = new AtomicReference<>();

        registry.registerFilter(new AdmissionFilter() {
            @Override
            public String name() {
                return "test-authenticator";
            }

            @Override
            public AdmissionPhase phase() {
                return AdmissionPhase.AUTHENTICATION;
            }

            @Override
            public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
                PrincipalContext principal = currentPrincipal.get();
                if (principal != null) {
                    AdmissionContext.current().authenticate(principal);
                }
                return true;
            }
        });
    }

    @Test
    @DisplayName("filter attributes match specification")
    void filterAttributes() {
        TenantMembershipFilter filter = TenantMembershipFilter.pathParameter("orgId");
        assertThat(filter.name()).isEqualTo(TenantMembershipFilter.NAME);
        assertThat(filter.phase()).isEqualTo(AdmissionPhase.AUTHORIZATION);

        TenantMembershipFilter withBypass = filter.bypassingRoles(Set.of("admin"));
        assertThat(withBypass).isNotSameAs(filter);
        assertThat(withBypass.name()).isEqualTo(TenantMembershipFilter.NAME);
        assertThat(withBypass.phase()).isEqualTo(AdmissionPhase.AUTHORIZATION);

        assertThatThrownBy(() -> TenantMembershipFilter.pathParameter(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantMembershipFilter.pathParameter(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("matching tenant admits and handler responds 200")
    void matchingTenantAdmitted() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/orgs/{orgId}/info");

        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.of(TENANT), Set.of()));
        RecordingExchange exchange = get("/orgs/" + TENANT + "/info");

        registry.handle(exchange);

        assertThat(exchange.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("other tenant is refused with 403 bare status")
    void otherTenantRefused() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/orgs/{orgId}/info");

        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.of(TENANT), Set.of()));
        RecordingExchange exchange = get("/orgs/" + OTHER_TENANT + "/info");

        registry.handle(exchange);

        assertThat(exchange.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.body()).isNull();
    }

    @Test
    @DisplayName("principal without tenant is refused with 403 bare status")
    void principalWithoutTenantRefused() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/orgs/{orgId}/info");

        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.empty(), Set.of()));
        RecordingExchange exchange = get("/orgs/" + TENANT + "/info");

        registry.handle(exchange);

        assertThat(exchange.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.body()).isNull();
    }

    @Test
    @DisplayName("malformed path parameter is refused with 403 bare status")
    void malformedTenantIdRefused() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/orgs/{orgId}/info");

        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.of(TENANT), Set.of()));

        RecordingExchange exchangeNonUuid = get("/orgs/not-a-valid-uuid/info");
        registry.handle(exchangeNonUuid);
        assertThat(exchangeNonUuid.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeNonUuid.body()).isNull();

        RecordingExchange exchangeUpper = get("/orgs/" + TENANT.toString().toUpperCase() + "/info");
        registry.handle(exchangeUpper);
        assertThat(exchangeUpper.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeUpper.body()).isNull();
    }

    @Test
    @DisplayName("principal holding a bypass role is admitted with 200 even for different or absent tenant")
    void bypassRoleAdmitted() {
        TenantMembershipFilter filter = TenantMembershipFilter.pathParameter("orgId")
                .bypassingRoles(Set.of("platform-admin", "auditor"));
        registry.registerFilter(filter);
        registerRoute("/orgs/{orgId}/info");

        // Bypass with different tenant
        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.of(TENANT), Set.of("platform-admin")));
        RecordingExchange exchangeOther = get("/orgs/" + OTHER_TENANT + "/info");
        registry.handle(exchangeOther);
        assertThat(exchangeOther.status()).isEqualTo(HttpStatus.OK);

        // Bypass with no tenant
        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.empty(), Set.of("auditor")));
        RecordingExchange exchangeNoTenant = get("/orgs/" + OTHER_TENANT + "/info");
        registry.handle(exchangeNoTenant);
        assertThat(exchangeNoTenant.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("route without the parameter passes untouched without principal or tenant checks")
    void routeWithoutParameterUntouched() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/health");

        // Anonymous request to route without {orgId}
        currentPrincipal.set(null);
        RecordingExchange exchange = get("/health");
        registry.handle(exchange);
        assertThat(exchange.status()).isEqualTo(HttpStatus.OK);

        // Request with principal without tenant to route without {orgId}
        currentPrincipal.set(new TestPrincipal(SUBJECT, Optional.empty(), Set.of()));
        RecordingExchange exchange2 = get("/health");
        registry.handle(exchange2);
        assertThat(exchange2.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("no principal results in 401 bare status response")
    void noPrincipalReturns401() {
        registry.registerFilter(TenantMembershipFilter.pathParameter("orgId"));
        registerRoute("/orgs/{orgId}/info");

        currentPrincipal.set(null);
        RecordingExchange exchange = get("/orgs/" + TENANT + "/info");

        registry.handle(exchange);

        assertThat(exchange.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.body()).isNull();
    }

    private void registerRoute(String pathPattern) {
        registry.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern(pathPattern)
                .actionName("Test#action")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());
    }

    private static RecordingExchange get(String path) {
        return new RecordingExchange(HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of()));
    }

    private record TestPrincipal(UUID principalId, Optional<UUID> tenantId, Set<String> roles) implements PrincipalContext {
        @Override
        public Set<String> scopes() {
            return Set.of();
        }

        @Override
        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }

    private static final class RecordingExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse response;
        private HttpStatus status;

        RecordingExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.response = response;
            this.status = response.status();
        }

        @Override
        public void respond(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }

        public String body() {
            if (response == null || !response.hasBody()) {
                return null;
            }
            return StandardCharsets.UTF_8.decode(response.body().segment().asByteBuffer()).toString();
        }

        public Optional<String> header(String name) {
            if (response == null) {
                return Optional.empty();
            }
            return response.headers().stream()
                    .filter(h -> h.nameEqualsIgnoreCase(name))
                    .map(HttpHeader::value)
                    .findFirst();
        }
    }
}
