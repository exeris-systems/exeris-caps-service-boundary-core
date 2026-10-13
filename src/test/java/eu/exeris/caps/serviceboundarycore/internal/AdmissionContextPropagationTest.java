package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.security.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admission context propagation from filters to the handler")
class AdmissionContextPropagationTest {

    private static final UUID SUBJECT = UUID.fromString("7d3c1f0e-2b4a-4c8e-9f10-1a2b3c4d5e6f");
    private static final UUID TENANT = UUID.fromString("0b9e8d7c-6a5f-4e3d-8c2b-1a0f9e8d7c6b");

    private DefaultApiSurfaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
    }

    @Test
    @DisplayName("the handler runs in the RequestContext whose principal and tenant a filter established")
    void handlerSeesAuthenticatedPrincipal() {
        TestPrincipal principal = new TestPrincipal(SUBJECT, Optional.of(TENANT));
        registry.registerFilter(filter("auth", AdmissionPhase.AUTHENTICATION, (ex, rm) -> {
            AdmissionContext.current().authenticate(principal);
            return true;
        }));
        AtomicReference<RequestContext> seen = new AtomicReference<>();
        route(ex -> {
            seen.set(RequestContext.current());
            ex.respond(HttpStatus.OK);
        });

        RecordingExchange exchange = get();
        registry.handle(exchange);

        assertThat(exchange.status).isEqualTo(HttpStatus.OK);
        assertThat(seen.get().principal()).contains(principal);
        assertThat(seen.get().tenantId()).isEqualTo(TENANT.toString());
    }

    @Test
    @DisplayName("a later filter reads the principal an earlier filter established")
    void laterFilterSeesPrincipal() {
        TestPrincipal principal = new TestPrincipal(SUBJECT, Optional.empty());
        AtomicReference<Optional<PrincipalContext>> seenByAuthz = new AtomicReference<>();
        registry.registerFilter(filter("auth", AdmissionPhase.AUTHENTICATION, (ex, rm) -> {
            AdmissionContext.current().authenticate(principal);
            return true;
        }));
        registry.registerFilter(filter("authz", AdmissionPhase.AUTHORIZATION, (ex, rm) -> {
            seenByAuthz.set(RequestContext.current().principal());
            return true;
        }));
        route(ex -> ex.respond(HttpStatus.OK));

        registry.handle(get());

        assertThat(seenByAuthz.get()).contains(principal);
    }

    @Test
    @DisplayName("a second principal is refused, and the handler does not run")
    void secondPrincipalRefused() {
        registry.registerFilter(filter("a-auth", AdmissionPhase.AUTHENTICATION, (ex, rm) -> {
            AdmissionContext.current().authenticate(new TestPrincipal(SUBJECT, Optional.empty()));
            return true;
        }));
        registry.registerFilter(filter("b-auth", AdmissionPhase.AUTHENTICATION, (ex, rm) -> {
            AdmissionContext.current().authenticate(new TestPrincipal(UUID.randomUUID(), Optional.empty()));
            return true;
        }));
        AtomicReference<Boolean> handlerRan = new AtomicReference<>(false);
        route(ex -> {
            handlerRan.set(true);
            ex.respond(HttpStatus.OK);
        });

        RecordingExchange exchange = get();
        registry.handle(exchange);

        assertThat(exchange.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handlerRan.get()).isFalse();
    }

    @Test
    @DisplayName("the handler has no admission context, and without a filter it has no principal")
    void handlerCannotAuthenticate() {
        AtomicReference<Boolean> admissionBound = new AtomicReference<>();
        AtomicReference<RequestContext> seen = new AtomicReference<>();
        route(ex -> {
            admissionBound.set(AdmissionContext.isBound());
            seen.set(RequestContext.current());
            ex.respond(HttpStatus.OK);
        });

        registry.handle(get());

        assertThat(admissionBound.get()).isFalse();
        assertThat(seen.get().principal()).isEmpty();
    }

    @Test
    @DisplayName("execute outside handle runs the filters with a fresh admission context")
    void executeOutsideHandle() {
        registry.registerFilter(filter("auth", AdmissionPhase.AUTHENTICATION, (ex, rm) -> {
            AdmissionContext.current().authenticate(new TestPrincipal(SUBJECT, Optional.empty()));
            return AdmissionContext.current().requestContext().principal().isPresent();
        }));
        ApiSurfaceRoute route = route(ex -> ex.respond(HttpStatus.OK));

        boolean passed = registry.execute(get(), new RouteMatch(route, java.util.Map.of()));

        assertThat(passed).isTrue();
    }

    private ApiSurfaceRoute route(HttpHandler handler) {
        ApiSurfaceRoute route = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/api/me")
                .actionName("Me#get")
                .handler(handler)
                .build();
        registry.registerRoute(route);
        return route;
    }

    private static RecordingExchange get() {
        return new RecordingExchange(HttpRequest.noBody(HttpMethod.GET, "/api/me", HttpVersion.HTTP_1_1, List.of()));
    }

    private static AdmissionFilter filter(String name, AdmissionPhase phase, BiPredicate<HttpExchange, RouteMatch> body) {
        return new AdmissionFilter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AdmissionPhase phase() {
                return phase;
            }

            @Override
            public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
                return body.test(exchange, routeMatch);
            }
        };
    }

    private record TestPrincipal(UUID principalId, Optional<UUID> tenantId) implements PrincipalContext {
        @Override
        public Set<String> roles() {
            return Set.of();
        }

        @Override
        public Set<String> scopes() {
            return Set.of();
        }
    }

    private static final class RecordingExchange implements HttpExchange {
        private final HttpRequest request;
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
            this.status = response.status();
        }

        @Override
        public void respond(HttpStatus status) {
            this.status = status;
        }
    }
}
