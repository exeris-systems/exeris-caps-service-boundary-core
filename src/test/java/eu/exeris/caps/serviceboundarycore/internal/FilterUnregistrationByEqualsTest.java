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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("unregisterFilter identifies a filter by equals, called on the registered filter")
class FilterUnregistrationByEqualsTest {

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
    @DisplayName("a registered filter that is equal to the argument is the one removed, whichever instance the argument is")
    void removesTheRegisteredFilterThatIsEqual() {
        ProbeFilter a = probe("a", AdmissionPhase.PRE_ADMISSION).equalToEvery(ProbeFilter.class);
        ProbeFilter b = probe("b", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(a);
        registry.registerFilter(b);

        assertThat(registry.unregisterFilter(b)).isTrue();
        int registeredCalls = a.equalsCalls.get();
        int argumentCalls = b.equalsCalls.get();

        assertThat(registeredCalls).isEqualTo(1);
        assertThat(argumentCalls).as("the argument's equals is not consulted").isZero();
        assertThat(registry.filters()).containsExactly(b);
        assertThat(serve()).containsExactly("b");
    }

    @Test
    @DisplayName("finding: a registered filter can remove another filter's registration by claiming equality with it")
    void aFilterCanClaimAnotherRegistration() {
        ProbeFilter thief = probe("thief", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        SpyFilter owner = new SpyFilter("owner", AdmissionPhase.AUTHENTICATION, trace);
        SpyFilter bystander = new SpyFilter("bystander", AdmissionPhase.AUTHORIZATION, trace);
        registry.registerFilter(thief);
        registry.registerFilter(owner);
        registry.registerFilter(bystander);

        // The owner removes its own registration; the registered filter that claims equality goes instead.
        boolean removed = registry.unregisterFilter(owner);

        assertThat(removed).isTrue();
        assertThat(registry.filters()).containsExactly(owner, bystander);
        assertThat(serve()).containsExactly("owner", "bystander");
    }

    @Test
    @DisplayName("the first registered filter that is equal is removed, once, and the search stops there")
    void firstEqualWins() {
        ProbeFilter first = probe("first", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        ProbeFilter second = probe("second", AdmissionPhase.AUTHENTICATION).equalToEvery(AdmissionFilter.class);
        registry.registerFilter(first);
        registry.registerFilter(second);

        assertThat(registry.unregisterFilter(probe("argument", AdmissionPhase.AUTHORIZATION))).isTrue();
        int secondCalls = second.equalsCalls.get();

        assertThat(secondCalls).isZero();
        assertThat(registry.filters()).containsExactly(second);
    }

    @Test
    @DisplayName("a registered filter that is not equal to its own argument cannot be removed through it")
    void equalityIsOneWay() {
        ProbeFilter registered = probe("registered", AdmissionPhase.PRE_ADMISSION);
        ProbeFilter claimant = probe("claimant", AdmissionPhase.PRE_ADMISSION).equalToEvery(AdmissionFilter.class);
        registry.registerFilter(registered);

        assertThat(registry.unregisterFilter(claimant)).isFalse();

        assertThat(registry.filters()).containsExactly(registered);
        assertThat(claimant.equalsCalls.get()).isZero();
    }

    @Test
    @DisplayName("an equals that throws leaves the registry unchanged and the lock released, wherever in the list it throws")
    void equalsThatThrowsChangesNothing() {
        for (int position = 0; position < 3; position++) {
            DefaultApiSurfaceRegistry local = new DefaultApiSurfaceRegistry();
            local.registerRoute(Fixtures.open("/p"));
            List<AdmissionFilter> registered = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ProbeFilter filter = probe("f" + i, AdmissionPhase.AUTHENTICATION);
                if (i == position) {
                    filter.equalsRule(other -> { throw new IllegalStateException("equals"); });
                }
                registered.add(filter);
                local.registerFilter(filter);
            }
            List<AdmissionFilter> before = local.filters();
            ProbeFilter stranger = probe("stranger", AdmissionPhase.AUTHENTICATION);

            assertThatThrownBy(() -> local.unregisterFilter(stranger)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("equals");

            assertThat(local.filters()).isEqualTo(before);
            trace.clear();
            local.handle(ScriptedExchange.get("/p"));
            assertThat(trace).containsExactly("f0", "f1", "f2");
            local.registerFilter(probe("later", AdmissionPhase.AUTHORIZATION));
            local.freeze();
            assertThat(local.filters()).hasSize(4);
        }
    }

    @Test
    @DisplayName("an equal but different instance removes the registration it equals and reserves nothing")
    void equalInstanceAfterRename() {
        SpyFilter registered = new SpyFilter("original", AdmissionPhase.AUTHENTICATION, trace);
        ProbeFilter holder = probe("holder", AdmissionPhase.PRE_ADMISSION);
        registry.registerFilter(holder);
        ProbeFilter keyed = probe("keyed", AdmissionPhase.AUTHENTICATION);
        ProbeFilter twin = probe("twin", AdmissionPhase.RATE_LIMITING);
        keyed.equalsRule(other -> other == keyed || other == twin);
        registry.registerFilter(keyed);
        registry.registerFilter(registered);

        assertThat(registry.unregisterFilter(twin)).isTrue();

        assertThat(registry.filters()).containsExactly(holder, registered);
        // The removed registration's captured name is free again; the twin's own name was never reserved.
        registry.registerFilter(probe("keyed", AdmissionPhase.AUTHENTICATION));
        registry.registerFilter(twin);
        assertThat(registry.filters()).hasSize(4);
        assertThat(registry.unregisterFilter(twin)).isTrue();
        assertThat(registry.unregisterFilter(twin)).as("second removal of the same argument").isFalse();
    }

    @Test
    @DisplayName("a filter that reports another name after registration is still removed by equality, and its captured name is freed")
    void renamedFilterRemovedAndNameFreed() {
        SpyFilter spy = new SpyFilter("captured", AdmissionPhase.AUTHENTICATION, trace);
        registry.registerFilter(spy);
        spy.reportsName("live");

        assertThat(registry.unregisterFilter(spy)).isTrue();

        registry.registerFilter(new SpyFilter("captured", AdmissionPhase.AUTHENTICATION, trace));
        registry.registerFilter(new SpyFilter("live", AdmissionPhase.AUTHENTICATION, trace));
        assertThat(registry.filters()).hasSize(2);
    }

    @Test
    @DisplayName("while frozen, unregisterFilter is refused before any equals is called")
    void frozenRefusesBeforeEquals() {
        ProbeFilter filter = probe("f", AdmissionPhase.AUTHENTICATION);
        registry.registerFilter(filter);
        registry.freeze();

        assertThatThrownBy(() -> registry.unregisterFilter(filter)).isInstanceOf(IllegalStateException.class);

        assertThat(filter.equalsCalls.get()).isZero();
    }
}
