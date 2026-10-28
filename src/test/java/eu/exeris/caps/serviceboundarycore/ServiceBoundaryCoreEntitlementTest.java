package eu.exeris.caps.serviceboundarycore;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.tck.contract.AbstractEntitlementRequirementTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("exeris-caps-service-boundary-core: EntitlementRequirement")
class ServiceBoundaryCoreEntitlementTest extends AbstractEntitlementRequirementTck {

    @Override
    protected Class<? extends EntitlementRequirement> providerClass() {
        return ServiceBoundaryCoreEntitlement.class;
    }

    @Test
    @DisplayName("ServiceLoader discovers the provider from the cap's own service registration")
    void serviceLoaderDiscoversTheProvider() {
        List<Class<? extends EntitlementRequirement>> types = ServiceLoader.load(EntitlementRequirement.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .toList();

        assertThat(types).contains(ServiceBoundaryCoreEntitlement.class);
    }

    @Test
    @DisplayName("It requires exactly this cap's own capability identifier")
    void requiresOnlyItsOwnCapability() {
        assertThat(new ServiceBoundaryCoreEntitlement().requiredCapabilities()).containsExactly("exeris-caps-service-boundary-core");
        assertThat(ServiceBoundaryCoreEntitlement.CAPABILITY_ID).isEqualTo("exeris-caps-service-boundary-core");
    }

    @Test
    @DisplayName("It declares the COMMERCIAL entitlement class, stable across calls")
    void entitlementClassIsCommercialAndStable() {
        ServiceBoundaryCoreEntitlement entitlement = new ServiceBoundaryCoreEntitlement();
        EntitlementClass first = entitlement.entitlementClass();

        assertThat(first).isEqualTo(EntitlementClass.COMMERCIAL);
        assertThat(entitlement.entitlementClass()).isSameAs(first);
        assertThat(new ServiceBoundaryCoreEntitlement().entitlementClass()).isEqualTo(EntitlementClass.COMMERCIAL);
    }
}
