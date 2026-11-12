package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ProbeFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.SpyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static eu.exeris.caps.serviceboundarycore.testsupport.Identity.assertSameInstances;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("unregisterFilter identifies a filter by reference and never asks it anything")
class FilterUnregistrationByReferenceTest {

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private DefaultApiSurfaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(Fixtures.open("/p"));
    }

    private List<String> serve() {
        trace.clear();
        registry.handle(ScriptedExchange.get("/p"));
        return List.copyOf(trace);
    }

    private ProbeFilter probe(String name, AdmissionPhase phase) {
        return new ProbeFilter(name, phase, trace);
    }

    @Test
    @DisplayName("the registered instance is removed, the others stay in order, and no equals, hashCode or toString is called")
    void sameInstanceRemoves() {
        ProbeFilter a = probe("a", AdmissionPhase.PRE_ADMISSION).equalToEvery(ProbeFilter.class);
        ProbeFilter b = probe("b", AdmissionPhase.AUTHENTICATION).equalToEvery(ProbeFilter.class);
        ProbeFilter c = probe("c", AdmissionPhase.AUTHORIZATION).equalToEvery(ProbeFilter.class);
        registry.registerFilter(a);
        registry.registerFilter(b);
        registry.registerFilter(c);

        boolean removed = registry.unregisterFilter(b);
        List<AdmissionFilter> remaining = registry.filters();
        List<String> ran = serve();

        assertThat(removed).isTrue();
        assertSameInstances(remaining, a, c);
        assertThat(ran).containsExactly("a", "c");
        assertThat(a.objectMethodCalls() + b.objectMethodCalls() + c.objectMethodCalls()).isZero();
    }

    @Test
    @DisplayName("a second removal of the same instance returns false")
    void secondRemovalIsFalse() {
        ProbeFilter a = probe("a", AdmissionPhase.PRE_ADMISSION);
        registry.registerFilter(a);

        assertThat(registry.unregisterFilter(a)).isTrue();
        assertThat(registry.unregisterFilter(a)).isFalse();
        assertThat(registry.filters()).isEmpty();
    }

    @Test
    @DisplayName("an instance that equals the registered one but is not it returns false and removes nothing; its name stays unreserved")
    void equalDifferentInstanceRemovesNothing() {
        ProbeFilter registered = probe("registered", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        ProbeFilter twin = probe("twin", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        registry.registerFilter(registered);

        boolean removed = registry.unregisterFilter(twin);
        List<AdmissionFilter> remaining = registry.filters();
        List<String> ran = serve();

        assertThat(removed).isFalse();
        assertSameInstances(remaining, registered);
        assertThat(ran).containsExactly("registered");
        assertThat(registered.objectMethodCalls() + twin.objectMethodCalls()).isZero();
        registry.registerFilter(twin);
        assertThat(registry.filters()).hasSize(2);
    }

    @Test
    @DisplayName("a registered filter that claims equality with every filter is not removed in place of the caller's own")
    void aFilterCannotBeRemovedInPlaceOfAnother() {
        ProbeFilter claimant = probe("claimant", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        SpyFilter owner = new SpyFilter("owner", AdmissionPhase.AUTHENTICATION, trace);
        SpyFilter bystander = new SpyFilter("bystander", AdmissionPhase.AUTHORIZATION, trace);
        registry.registerFilter(claimant);
        registry.registerFilter(owner);
        registry.registerFilter(bystander);

        boolean removed = registry.unregisterFilter(owner);
        List<AdmissionFilter> remaining = registry.filters();
        List<String> ran = serve();

        assertThat(removed).isTrue();
        assertSameInstances(remaining, claimant, bystander);
        assertThat(ran).containsExactly("claimant", "bystander");
        assertThat(claimant.objectMethodCalls()).isZero();
    }

    @Test
    @DisplayName("a filter that claims equality with a registered one cannot remove it")
    void aClaimantCannotRemoveTheFilterItClaimsToEqual() {
        ProbeFilter registered = probe("registered", AdmissionPhase.PRE_ADMISSION);
        ProbeFilter claimant = probe("claimant", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        registry.registerFilter(registered);

        boolean removed = registry.unregisterFilter(claimant);
        List<AdmissionFilter> remaining = registry.filters();

        assertThat(removed).isFalse();
        assertSameInstances(remaining, registered);
        assertThat(registered.objectMethodCalls() + claimant.objectMethodCalls()).isZero();
    }

    @Test
    @DisplayName("an equals that throws is never called, so removal and everything after it are unaffected")
    void equalsThatThrowsIsNeverCalled() {
        List<ProbeFilter> filters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ProbeFilter filter = probe("f" + i, AdmissionPhase.AUTHENTICATION)
                    .equalsRule(other -> { throw new IllegalStateException("equals"); });
            filters.add(filter);
            registry.registerFilter(filter);
        }

        assertThat(registry.unregisterFilter(probe("stranger", AdmissionPhase.AUTHENTICATION))).isFalse();
        assertThat(registry.unregisterFilter(filters.get(1))).isTrue();
        List<String> ran = serve();

        assertThat(ran).containsExactly("f0", "f2");
        registry.registerFilter(probe("later", AdmissionPhase.AUTHORIZATION));
        registry.freeze();
        assertThat(registry.filters()).hasSize(3);
        for (ProbeFilter filter : filters) {
            assertThat(filter.objectMethodCalls()).isZero();
        }
    }

    @Test
    @DisplayName("a filter that reports another name after registration is removed by its own instance and its captured name is freed")
    void renamedFilterRemovedAndNameFreed() {
        SpyFilter spy = new SpyFilter("captured", AdmissionPhase.AUTHENTICATION, trace);
        registry.registerFilter(spy);
        spy.reportsName("live");

        assertThat(registry.unregisterFilter(spy)).isTrue();

        assertThat(spy.nameCalls.get()).as("removal does not ask the filter its name again").isEqualTo(1);
        registry.registerFilter(new SpyFilter("captured", AdmissionPhase.AUTHENTICATION, trace));
        registry.registerFilter(new SpyFilter("live", AdmissionPhase.AUTHENTICATION, trace));
        assertThat(registry.filters()).hasSize(2);
    }

    @Test
    @DisplayName("while frozen, removal is refused with the frozen exception and the filter stays registered")
    void frozenRefusesRemoval() {
        ProbeFilter filter = probe("f", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(filter);
        registry.freeze();

        assertThatThrownBy(() -> registry.unregisterFilter(filter)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The registry is frozen and can no longer be modified");

        assertSameInstances(registry.filters(), filter);
        assertThat(filter.objectMethodCalls()).isZero();
        assertThat(serve()).containsExactly("f");
    }

    @Test
    @DisplayName("during teardown the same instance is removed")
    void teardownRemovesTheSameInstance() {
        ProbeFilter filter = probe("f", AdmissionPhase.AUTHENTICATION);
        ProbeFilter twin = probe("twin", AdmissionPhase.AUTHENTICATION).equalToEvery(AdmissionFilter.class);
        registry.registerFilter(filter);
        registry.freeze();
        registry.beginTeardown();

        assertThat(registry.unregisterFilter(twin)).isFalse();
        assertThat(registry.unregisterFilter(filter)).isTrue();
        assertThat(registry.filters()).isEmpty();
        assertThat(filter.objectMethodCalls() + twin.objectMethodCalls()).isZero();
    }
}
