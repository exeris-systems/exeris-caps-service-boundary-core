package eu.exeris.caps.serviceboundarycore;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.internal.DefaultApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.internal.MatchedHttpExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Documentation as contract: the correlation id and the admission path's allocation")
class AllocationDocumentationTest {

    private static String normalised(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("README: the correlation id is generated on first read, by compare, hash or print too, unpredictable, one value, verbatim when explicit")
    void readmeCorrelationBullet() throws IOException {
        assertThat(normalised("README.md"))
                .contains("**The correlation id is generated on first read.**")
                .contains("A `RequestContext` built without a correlation id has none until `correlationId()` is first called on it "
                        + "(or it is compared, hashed or printed)")
                .contains("a request whose correlation id nobody reads never generates one")
                .contains("The id is unpredictable (`UUID.randomUUID()`)")
                .contains("once it exists every read on any thread returns the same value, as does every context admission derives for that request")
                .contains("An id set through `RequestContext.builder().correlationId(...)` is returned as it is.");

        RequestContext fresh = RequestContext.builder().build();
        String printed = fresh.toString();
        assertThat(printed).contains(fresh.correlationId());
        RequestContext hashed = RequestContext.builder().build();
        hashed.hashCode();
        assertThat(hashed.hashCode()).isEqualTo(hashed.hashCode());
        assertThat(UUID.fromString(RequestContext.builder().build().correlationId()).version()).isEqualTo(4);
        assertThat(RequestContext.builder().correlationId("as-is").build().correlationId()).isEqualTo("as-is");
    }

    @Test
    @DisplayName("ROADMAP: the allocation item states what was removed and what remains, in the words the code supports")
    void roadmapAllocationItem() throws IOException {
        String roadmap = normalised("ROADMAP.md");
        assertThat(roadmap)
                .contains("### The admission path allocates on every request")
                .contains("the registry keeps one pre-sorted array and reads it once per request")
                .contains("the match is built at registration")
                .contains("the attribute map of the admission context (allocated on the first write)")
                .contains("built once per change of admission state, and without a builder")
                .contains("the id is generated when something first reads it, and is still `UUID.randomUUID()`")
                .contains("the substring made for a query string or a trailing slash")
                .contains("`MatchedHttpExchange`, one per matched request")
                .contains("at least one, because the handler runs in it")
                .contains("and another for each filter that runs after an earlier filter changed the state")
                .contains("A correlation id costs a `SecureRandom` draw, a `UUID` and its string at the first read, by design")
                .contains("each parameterised route of the same HTTP method scanned before the match costs again, so the cost grows with the number of routes");
    }

    @Test
    @DisplayName("ROADMAP: one request context per change of state, at least one for the handler, and a MatchedHttpExchange per matched request")
    void requestContextCountFollowsStateChanges() {
        // no filter: the handler still runs in one context
        DefaultApiSurfaceRegistry bare = new DefaultApiSurfaceRegistry();
        AtomicReference<RequestContext> bareView = new AtomicReference<>();
        AtomicReference<Object> bareExchange = new AtomicReference<>();
        bare.registerRoute(Fixtures.open("/p", ex -> {
            bareView.set(RequestContext.current());
            bareExchange.set(ex);
            ex.respond(HttpStatus.OK);
        }));
        bare.handle(ScriptedExchange.get("/p"));
        assertThat(bareView.get()).isNotNull();
        assertThat(bareExchange.get()).isInstanceOf(MatchedHttpExchange.class);

        // filters that change nothing share the context with the handler; each change starts another one
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<RequestContext> views = new ArrayList<>();
        registry.registerFilter(Fixtures.filter("a", AdmissionPhase.PRE_ADMISSION, m -> views.add(RequestContext.current())));
        registry.registerFilter(Fixtures.filter("b", AdmissionPhase.RATE_LIMITING, m -> {
            views.add(RequestContext.current());
            AdmissionContext.current().attribute("k", "v");
        }));
        registry.registerFilter(Fixtures.filter("c", AdmissionPhase.AUTHENTICATION, m -> views.add(RequestContext.current())));
        registry.registerFilter(Fixtures.filter("d", AdmissionPhase.AUTHORIZATION, m -> views.add(RequestContext.current())));
        AtomicReference<RequestContext> handlerView = new AtomicReference<>();
        registry.registerRoute(Fixtures.open("/p", ex -> {
            handlerView.set(RequestContext.current());
            ex.respond(HttpStatus.OK);
        }));
        registry.handle(ScriptedExchange.get("/p"));
        assertThat(views.get(0)).isSameAs(views.get(1));
        assertThat(views.get(2)).isNotSameAs(views.get(1)).isSameAs(views.get(3));
        assertThat(handlerView.get()).isSameAs(views.get(3));
        assertThat(Set.copyOf(views.stream().map(System::identityHashCode).toList())).hasSize(2);
    }

    @Test
    @DisplayName("a request whose filters never read the correlation id leaves none generated in any of its views")
    void unreadIdIsNeverGenerated() throws Exception {
        java.lang.reflect.Field field = Class.forName("eu.exeris.caps.serviceboundarycore.internal.DefaultRequestContext")
                .getDeclaredField("generatedCorrelationId");
        field.setAccessible(true);
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<RequestContext> views = new ArrayList<>();
        registry.registerFilter(Fixtures.filter("a", AdmissionPhase.AUTHENTICATION, m -> views.add(RequestContext.current())));
        registry.registerRoute(Fixtures.open("/p", ex -> {
            views.add(RequestContext.current());
            ex.respond(HttpStatus.OK);
        }));
        registry.handle(ScriptedExchange.get("/p"));
        assertThat(views).isNotEmpty();
        for (RequestContext view : views) {
            assertThat(field.get(view)).isNull();
        }
        assertThat(Pattern.matches("[0-9a-f-]{36}", views.get(0).correlationId())).isTrue();
        assertThat(field.get(views.get(0))).isNotNull();
    }
}
