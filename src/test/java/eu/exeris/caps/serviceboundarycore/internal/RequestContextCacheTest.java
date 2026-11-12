package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures.Person;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.PrincipalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The request context an admission context hands out")
class RequestContextCacheTest {

    private static final UUID TENANT = UUID.fromString("0b9e8d7c-6a5f-4e3d-8c2b-1a0f9e8d7c6b");
    private static final UUID SESSION = UUID.fromString("5e55105e-0000-4000-8000-000000000001");

    // ---- the independent model -----------------------------------------------------------------------------------

    /** The state an admission context should hold, kept by the test without sharing code with production. */
    private static final class Model {
        PrincipalContext principal;
        UUID session;
        String tenant;
        final Map<String, Object> attributes = new LinkedHashMap<>();

        Model(RequestContext seed) {
            if (seed != null) {
                principal = seed.principal().orElse(null);
                tenant = seed.tenantId().orElse(null);
                attributes.putAll(seed.attributes());
            }
        }
    }

    private static void assertView(RequestContext view, Model model, String correlationId, String label) {
        assertThat(view.principal()).as(label + " principal").isEqualTo(Optional.ofNullable(model.principal));
        assertThat(view.sessionId()).as(label + " session").isEqualTo(Optional.ofNullable(model.session));
        assertThat(view.tenantId()).as(label + " tenant").isEqualTo(Optional.ofNullable(model.tenant));
        assertThat(view.attributes()).as(label + " attributes").isEqualTo(model.attributes);
        assertThat(view.correlationId()).as(label + " correlation id").isEqualTo(correlationId);
        for (Map.Entry<String, Object> entry : model.attributes.entrySet()) {
            assertThat(view.attribute(entry.getKey())).as(label + " attribute " + entry.getKey())
                    .contains(entry.getValue());
        }
    }

    private static RequestContext host(String tenant, Map<String, Object> attributes) {
        RequestContext.Builder builder = RequestContext.builder().correlationId("host-correlation");
        if (tenant != null) {
            builder.tenantId(tenant);
        }
        return builder.attributes(attributes).build();
    }

    private static void within(RequestContext host, Runnable action) {
        if (host == null) {
            action.run();
        } else {
            RequestContext.runWith(host, action);
        }
    }

    // ---- basic behaviour ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("without an intervening write, repeated requestContext() calls return the same equal view")
    void repeatedCallsAreStable() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext first = admission.requestContext();
        assertThat(admission.requestContext()).isSameAs(first).isEqualTo(first);
        assertThat(admission.requestContext().attributes()).isEmpty();
    }

    @Test
    @DisplayName("after authenticate the next view reflects principal, session and tenant")
    void authenticateIsReflected() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext before = admission.requestContext();
        Person person = Fixtures.person(1, TENANT);
        admission.authenticate(person, SESSION);
        RequestContext after = admission.requestContext();
        assertThat(after).isNotSameAs(before);
        assertThat(after.principal()).contains(person);
        assertThat(after.sessionId()).contains(SESSION);
        assertThat(after.tenantId()).contains(TENANT.toString());
        assertThat(after.correlationId()).isEqualTo(before.correlationId());
        assertThat(before.principal()).isEmpty();
        assertThat(before.sessionId()).isEmpty();
        assertThat(before.tenantId()).isEmpty();
    }

    @Test
    @DisplayName("attribute writes are reflected; an absent key removed or an equal value set leaves an equal view")
    void attributeWritesAreReflected() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext empty = admission.requestContext();

        admission.attribute("absent", null);
        assertThat(admission.requestContext()).isSameAs(empty);

        admission.attribute("k", "v");
        RequestContext withK = admission.requestContext();
        assertThat(withK).isNotSameAs(empty);
        assertThat(withK.attribute("k")).contains("v");

        admission.attribute("k", "v");
        assertThat(admission.requestContext()).isEqualTo(withK);
        assertThat(admission.requestContext().attributes()).isEqualTo(Map.of("k", "v"));

        admission.attribute("k", "w");
        assertThat(admission.requestContext().attribute("k")).contains("w");
        assertThat(withK.attribute("k")).contains("v");

        RequestContext before = admission.requestContext();
        admission.attribute("k", null);
        RequestContext removed = admission.requestContext();
        assertThat(removed).isNotSameAs(before);
        assertThat(removed.attribute("k")).isEmpty();
        assertThat(removed.attributes()).isEmpty();
        assertThat(before.attribute("k")).contains("w");
    }

    @Test
    @DisplayName("a context captured before a write keeps its old state; the handler sees the final state")
    void capturedViewsKeepTheirState() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<RequestContext> captured = new ArrayList<>();
        Person person = Fixtures.person(3, TENANT);
        registry.registerFilter(Fixtures.filter("a", AdmissionPhase.PRE_ADMISSION, m -> {
            captured.add(RequestContext.current());
            AdmissionContext.current().attribute("one", 1);
        }));
        registry.registerFilter(Fixtures.filter("b", AdmissionPhase.AUTHENTICATION, m -> {
            captured.add(RequestContext.current());
            AdmissionContext.current().authenticate(person, SESSION);
            AdmissionContext.current().attribute("two", 2);
            AdmissionContext.current().attribute("one", null);
        }));
        registry.registerFilter(Fixtures.filter("c", AdmissionPhase.POST_ADMISSION, m -> captured.add(RequestContext.current())));
        AtomicReference<RequestContext> handlerView = new AtomicReference<>();
        registry.registerRoute(Fixtures.route(HttpMethod.GET, "/p", List.of(RouteRequirement.authenticated()), ex -> {
            handlerView.set(RequestContext.current());
            ex.respond(HttpStatus.OK);
        }));
        ScriptedExchange exchange = ScriptedExchange.get("/p");
        registry.handle(exchange);
        assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);

        RequestContext seenByA = captured.get(0);
        RequestContext seenByB = captured.get(1);
        RequestContext seenByC = captured.get(2);
        assertThat(seenByA.attributes()).isEmpty();
        assertThat(seenByA.principal()).isEmpty();
        assertThat(seenByB.attributes()).isEqualTo(Map.of("one", 1));
        assertThat(seenByB.principal()).isEmpty();
        assertThat(seenByC.attributes()).isEqualTo(Map.of("two", 2));
        assertThat(seenByC.principal()).contains(person);
        assertThat(seenByC.sessionId()).contains(SESSION);
        assertThat(handlerView.get()).isSameAs(seenByC);
        assertThat(handlerView.get().attributes()).isEqualTo(Map.of("two", 2));
    }

    @Test
    @DisplayName("a principal whose tenantId() fails leaves a view consistent with whether the principal was recorded")
    void failingTenantStillInvalidates() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext before = admission.requestContext();
        Fixtures.TenantFails failing = new Fixtures.TenantFails(new UUID(1, 1));
        assertThatThrownBy(() -> admission.authenticate(failing)).isInstanceOf(IllegalArgumentException.class);
        RequestContext after = admission.requestContext();
        boolean recorded = after.principal().isPresent();
        assertThat(recorded).as("a second principal is refused exactly when the first was recorded").isEqualTo(
                refuses(() -> admission.authenticate(Fixtures.person(9))));
        if (recorded) {
            assertThat(after).isNotSameAs(before);
            assertThat(after.principal()).contains(failing);
        }
        assertThat(before.principal()).isEmpty();
    }

    private static boolean refuses(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException refused) {
            return true;
        }
    }

    @Test
    @DisplayName("with a host tenant bound, a principal whose tenantId() fails changes nothing and the cache stays valid")
    void failingTenantWithHostTenantChangesNothing() {
        within(host(TENANT.toString(), Map.of()), () -> {
            DefaultAdmissionContext admission = DefaultAdmissionContext.open();
            RequestContext before = admission.requestContext();
            assertThatThrownBy(() -> admission.authenticate(new Fixtures.TenantFails(new UUID(1, 2))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(admission.requestContext().principal().isPresent())
                    .isEqualTo(refuses(() -> admission.authenticate(Fixtures.person(9))));
            assertThat(before.principal()).isEmpty();
        });
    }

    @Test
    @DisplayName("another thread calling any method is refused and does not disturb the owner")
    void otherThreadsAreRefused() throws Exception {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext view = admission.requestContext();
        List<Throwable> refusals = new ArrayList<>();
        List<Consumer<DefaultAdmissionContext>> calls = List.of(
                a -> a.authenticate(Fixtures.person(1)),
                a -> a.authenticate(Fixtures.person(1), SESSION),
                a -> a.attribute("k", "v"),
                a -> a.attribute("k", null),
                DefaultAdmissionContext::requestContext,
                DefaultAdmissionContext::seal);
        Thread other = new Thread(() -> {
            for (Consumer<DefaultAdmissionContext> call : calls) {
                try {
                    call.accept(admission);
                    refusals.add(null);
                } catch (IllegalStateException refused) {
                    refusals.add(refused);
                }
            }
        });
        other.start();
        other.join();
        assertThat(refusals).hasSize(calls.size()).doesNotContainNull();
        assertThat(admission.requestContext()).isSameAs(view);
        admission.attribute("still", "works");
        assertThat(admission.requestContext().attribute("still")).contains("works");
    }

    @Test
    @DisplayName("an admission context opened on a virtual thread is owned by that thread")
    void virtualThreadOwnsItsContext() throws Exception {
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<DefaultAdmissionContext> escaped = new AtomicReference<>();
        Thread virtual = Thread.ofVirtual().start(() -> {
            try {
                DefaultAdmissionContext admission = DefaultAdmissionContext.open();
                admission.attribute("k", "v");
                admission.requestContext();
                escaped.set(admission);
            } catch (Throwable t) {
                ownerFailure.set(t);
            }
        });
        virtual.join();
        assertThat(ownerFailure.get()).isNull();
        assertThatThrownBy(() -> escaped.get().requestContext()).isInstanceOf(IllegalStateException.class);
    }

    // ---- ordering of mutators ----------------------------------------------------------------------------------

    private record Mutator(String name, Consumer<AdmissionContext> apply, Consumer<Model> expect, boolean authenticates) { }

    private static final Person P1 = Fixtures.person(11);
    private static final Person P2 = Fixtures.person(12, TENANT);
    private static final Person P3 = Fixtures.person(13, UUID.fromString("00000000-0000-4000-8000-0000000000aa"));

    private static List<Mutator> mutators() {
        List<Mutator> list = new ArrayList<>();
        list.add(new Mutator("auth P1", a -> a.authenticate(P1), m -> m.principal = P1, true));
        list.add(new Mutator("auth P1+session", a -> a.authenticate(P1, SESSION), m -> {
            m.principal = P1;
            m.session = SESSION;
        }, true));
        list.add(new Mutator("auth P2(tenant)", a -> a.authenticate(P2), m -> {
            m.principal = P2;
            m.tenant = TENANT.toString();
        }, true));
        list.add(new Mutator("set new", a -> a.attribute("n", 1), m -> m.attributes.put("n", 1), false));
        list.add(new Mutator("set existing", a -> a.attribute("a", "changed"), m -> m.attributes.put("a", "changed"), false));
        list.add(new Mutator("set equal", a -> a.attribute("a", 1), m -> m.attributes.put("a", 1), false));
        list.add(new Mutator("remove present", a -> a.attribute("a", null), m -> m.attributes.remove("a"), false));
        list.add(new Mutator("remove absent", a -> a.attribute("zz", null), m -> m.attributes.remove("zz"), false));
        return list;
    }

    @Test
    @DisplayName("every pair of mutators with a read before, between and after leaves a view equal to one built from the full state")
    void everyOrderingOfTwoMutators() {
        List<RequestContext> seeds = new ArrayList<>();
        seeds.add(null);
        seeds.add(host(null, Map.of("a", 1)));
        seeds.add(host(TENANT.toString(), Map.of("a", 1, "b", 2)));
        for (RequestContext seed : seeds) {
            for (Mutator first : mutators()) {
                for (Mutator second : mutators()) {
                    for (boolean readBetween : new boolean[] {false, true}) {
                        for (boolean readFirst : new boolean[] {false, true}) {
                            String label = (seed == null ? "no host" : "host " + seed.tenantId()) + ": " + first.name()
                                    + " then " + second.name() + (readBetween ? ", read between" : "")
                                    + (readFirst ? ", read first" : "");
                            within(seed, () -> run(seed, first, second, readFirst, readBetween, label));
                        }
                    }
                }
            }
        }
    }

    private static void run(RequestContext seed, Mutator first, Mutator second, boolean readFirst,
                            boolean readBetween, String label) {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        Model model = new Model(seed);
        RequestContext initial = admission.requestContext();
        String correlation = initial.correlationId();
        if (seed != null) {
            assertThat(correlation).isEqualTo("host-correlation");
        }
        if (readFirst) {
            assertView(admission.requestContext(), model, correlation, label + " [initial]");
        }
        List<RequestContext> history = new ArrayList<>(List.of(initial));
        List<String> frozen = new ArrayList<>(List.of(describe(initial)));
        apply(admission, model, first);
        if (readBetween) {
            RequestContext between = admission.requestContext();
            assertView(between, model, correlation, label + " [between]");
            history.add(between);
            frozen.add(describe(between));
        }
        apply(admission, model, second);
        assertView(admission.requestContext(), model, correlation, label + " [after]");
        assertView(admission.requestContext(), model, correlation, label + " [after, again]");
        for (int i = 0; i < history.size(); i++) {
            assertThat(describe(history.get(i))).as(label + " earlier view " + i + " unchanged").isEqualTo(frozen.get(i));
        }
    }

    /** Applies the mutator to the admission context and, when the outcome is defined, to the model. */
    private static void apply(DefaultAdmissionContext admission, Model model, Mutator mutator) {
        boolean principalPresent = model.principal != null;
        boolean tenantConflict = false;
        if (mutator.authenticates() && !principalPresent && model.tenant != null) {
            Model probe = new Model(null);
            mutator.expect().accept(probe);
            tenantConflict = probe.tenant != null && !probe.tenant.equalsIgnoreCase(model.tenant);
        }
        if (mutator.authenticates() && principalPresent) {
            assertThatThrownBy(() -> mutator.apply().accept(admission)).isInstanceOf(IllegalStateException.class);
        } else if (tenantConflict) {
            assertThatThrownBy(() -> mutator.apply().accept(admission)).isInstanceOf(TenantConflictException.class);
        } else {
            mutator.apply().accept(admission);
            mutator.expect().accept(model);
        }
    }

    private static String describe(RequestContext view) {
        return view.principal() + "|" + view.sessionId() + "|" + view.tenantId() + "|" + new java.util.TreeMap<>(view.attributes());
    }

    @Test
    @DisplayName("authenticating twice with different principals keeps the first in every later view")
    void secondPrincipalNeverReplacesTheFirst() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        admission.authenticate(P1);
        RequestContext view = admission.requestContext();
        assertThatThrownBy(() -> admission.authenticate(P2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> admission.authenticate(P3, SESSION)).isInstanceOf(IllegalStateException.class);
        assertThat(admission.requestContext()).isSameAs(view);
        assertThat(admission.requestContext().principal()).contains(P1);
        assertThat(admission.requestContext().sessionId()).isEmpty();
        assertThat(admission.requestContext().tenantId()).isEmpty();
    }

    @Test
    @DisplayName("a filter that throws after authenticating: no later filter and no handler runs, and the next request starts without the principal")
    void throwAfterAuthenticating() {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        List<String> ran = new ArrayList<>();
        AtomicReference<Boolean> blowUp = new AtomicReference<>(true);
        registry.registerFilter(Fixtures.filter("a-auth", AdmissionPhase.AUTHENTICATION, m -> {
            ran.add("a-auth");
            AdmissionContext.current().authenticate(P1, SESSION);
            if (blowUp.get()) {
                throw new IllegalStateException("after authenticating");
            }
        }));
        registry.registerFilter(Fixtures.filter("b-later", AdmissionPhase.AUTHORIZATION, m -> {
            ran.add("b-later:" + RequestContext.current().principal().isPresent());
        }));
        List<Boolean> handlerSawPrincipal = new ArrayList<>();
        registry.registerRoute(Fixtures.route(HttpMethod.GET, "/p", List.of(RouteRequirement.authenticated()), ex -> {
            handlerSawPrincipal.add(RequestContext.current().principal().isPresent());
            ex.respond(HttpStatus.OK);
        }));
        ScriptedExchange failed = ScriptedExchange.get("/p");
        registry.handle(failed);
        assertThat(failed.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ran).containsExactly("a-auth");
        assertThat(handlerSawPrincipal).isEmpty();

        blowUp.set(false);
        ScriptedExchange next = ScriptedExchange.get("/p");
        registry.handle(next);
        assertThat(next.statuses()).containsExactly(HttpStatus.OK);
        assertThat(ran).containsExactly("a-auth", "a-auth", "b-later:true");
        assertThat(handlerSawPrincipal).containsExactly(true);

        registry.unregisterFilter(registry.filters().get(0));
        ScriptedExchange anonymous = ScriptedExchange.get("/p");
        registry.handle(anonymous);
        assertThat(anonymous.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("after seal the view is still readable and equal to the final state, and every write is refused without effect")
    void sealThenRead() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        admission.authenticate(P1, SESSION);
        admission.attribute("k", "v");
        RequestContext before = admission.requestContext();
        admission.seal();
        RequestContext after = admission.requestContext();
        assertThat(after).isSameAs(before);
        assertThatThrownBy(() -> admission.attribute("k", "w")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> admission.attribute("k", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> admission.attribute("absent", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> admission.authenticate(P2)).isInstanceOf(IllegalStateException.class);
        assertThat(admission.requestContext()).isSameAs(after);
        assertThat(after.attribute("k")).contains("v");
        assertThat(after.principal()).contains(P1);
    }

    // ---- attributes ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("without attributes the view is empty and unmodifiable, also after a write that removes the last one")
    void emptyAttributesAreUnmodifiable() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        Map<String, Object> view = admission.requestContext().attributes();
        assertThat(view).isEmpty();
        assertThatThrownBy(() -> view.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(view::clear).isInstanceOf(UnsupportedOperationException.class);
        admission.attribute("k", "v");
        admission.attribute("k", null);
        Map<String, Object> after = admission.requestContext().attributes();
        assertThat(after).isEmpty();
        assertThatThrownBy(() -> after.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a host-bound seed's attributes are visible before and after the first write")
    void seedAttributesVisible() {
        Map<String, Object> seedAttributes = new LinkedHashMap<>();
        seedAttributes.put("s1", "one");
        seedAttributes.put("s2", "two");
        seedAttributes.put("s3", "three");
        RequestContext seed = host(null, seedAttributes);
        within(seed, () -> {
            DefaultAdmissionContext admission = DefaultAdmissionContext.open();
            assertThat(admission.requestContext().attributes()).isEqualTo(seed.attributes());
            assertThat(List.copyOf(admission.requestContext().attributes().keySet()))
                    .isEqualTo(List.copyOf(seed.attributes().keySet()));
            admission.attribute("added", 1);
            assertThat(admission.requestContext().attributes())
                    .containsAllEntriesOf(Map.of("s1", "one", "s2", "two", "s3", "three"))
                    .containsEntry("added", 1).hasSize(4);
        });
    }

    @Test
    @DisplayName("an admission write never mutates the seed's map, and two requests sharing a seed do not see each other's attributes")
    void seedIsNeverMutatedAndRequestsAreIsolated() {
        RequestContext seed = host(TENANT.toString(), Map.of("s1", "one", "s2", "two"));
        Map<String, Object> seedBefore = Map.copyOf(seed.attributes());
        Map<String, Object> seedView = seed.attributes();
        within(seed, () -> {
            DefaultAdmissionContext a = DefaultAdmissionContext.open();
            DefaultAdmissionContext b = DefaultAdmissionContext.open();
            RequestContext a0 = a.requestContext();
            RequestContext b0 = b.requestContext();
            a.attribute("only-a", 1);
            a.attribute("s1", null);
            b.attribute("only-b", 2);
            b.attribute("s2", "changed");
            assertThat(seed.attributes()).isEqualTo(seedBefore);
            assertThat(seedView).isEqualTo(seedBefore);
            assertThat(a.requestContext().attributes()).isEqualTo(Map.of("only-a", 1, "s2", "two"));
            assertThat(b.requestContext().attributes()).isEqualTo(Map.of("only-b", 2, "s1", "one", "s2", "changed"));
            assertThat(a0.attributes()).isEqualTo(seedBefore);
            assertThat(b0.attributes()).isEqualTo(seedBefore);
            assertThat(seed.attribute("only-a")).isEmpty();
            assertThat(seed.attribute("s1")).contains("one");
            assertThat(DefaultAdmissionContext.open().requestContext().attributes()).isEqualTo(seedBefore);
        });
    }

    @Test
    @DisplayName("a view built while the attributes were shared or owned never changes when later writes follow")
    void viewsAreSnapshots() {
        RequestContext seed = host(null, Map.of("s", "seed"));
        within(seed, () -> {
            DefaultAdmissionContext admission = DefaultAdmissionContext.open();
            RequestContext shared = admission.requestContext();
            admission.attribute("k1", 1);
            RequestContext owned1 = admission.requestContext();
            admission.attribute("k2", 2);
            admission.attribute("k1", null);
            RequestContext owned2 = admission.requestContext();
            admission.attribute("k3", 3);
            assertThat(shared.attributes()).isEqualTo(Map.of("s", "seed"));
            assertThat(owned1.attributes()).isEqualTo(Map.of("s", "seed", "k1", 1));
            assertThat(owned2.attributes()).isEqualTo(Map.of("s", "seed", "k2", 2));
            assertThatThrownBy(() -> owned2.attributes().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> owned2.attributes().remove("k2")).isInstanceOf(UnsupportedOperationException.class);
        });
    }
}
