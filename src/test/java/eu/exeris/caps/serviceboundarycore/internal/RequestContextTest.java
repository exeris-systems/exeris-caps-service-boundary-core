package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestContext")
class RequestContextTest {

    @Nested
    @DisplayName("builder and immutability")
    class BuilderTests {

        @Test
        @DisplayName("builds with default values")
        void buildsWithDefaults() {
            RequestContext ctx = RequestContext.builder().build();

            assertThat(ctx.tenantId()).isEqualTo("default");
            assertThat(ctx.correlationId()).isNotBlank();
            assertThat(ctx.principal()).isEmpty();
            assertThat(ctx.attributes()).isEmpty();
        }

        @Test
        @DisplayName("builds with custom tenant and attributes")
        void buildsWithCustomValues() {
            UUID principalId = UUID.randomUUID();
            PrincipalContext principal = new TestPrincipalContext(principalId, Optional.of(UUID.randomUUID()));

            RequestContext ctx = RequestContext.builder()
                    .tenantId("tenant-acme")
                    .correlationId("corr-12345")
                    .principal(principal)
                    .attribute("client-ip", "192.168.1.1")
                    .attribute("trace-sampled", true)
                    .build();

            assertThat(ctx.tenantId()).isEqualTo("tenant-acme");
            assertThat(ctx.correlationId()).isEqualTo("corr-12345");
            assertThat(ctx.principal()).contains(principal);
            assertThat(ctx.attribute("client-ip")).contains("192.168.1.1");
            assertThat(ctx.attribute("trace-sampled")).contains(true);
            assertThat(ctx.attribute("non-existent")).isEmpty();
        }

        @Test
        @DisplayName("attributes map is unmodifiable")
        void attributesMapIsUnmodifiable() {
            RequestContext ctx = RequestContext.builder()
                    .attribute("k", "v")
                    .build();

            assertThatThrownBy(() -> ctx.attributes().put("other", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("ScopedValue context propagation")
    class ScopedValueTests {

        @Test
        @DisplayName("isBound is false and current() throws when unbound")
        void unboundBehavior() {
            assertThat(RequestContext.isBound()).isFalse();
            assertThatThrownBy(RequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No RequestContext is bound");
        }

        @Test
        @DisplayName("runWith binds context and unbinds on exit")
        void runWithBindsAndUnbinds() {
            RequestContext ctx = RequestContext.builder()
                    .tenantId("tenant-omega")
                    .correlationId("corr-omega-1")
                    .build();

            AtomicBoolean verified = new AtomicBoolean();

            RequestContext.runWith(ctx, () -> {
                assertThat(RequestContext.isBound()).isTrue();
                assertThat(RequestContext.current()).isSameAs(ctx);
                assertThat(RequestContext.current().tenantId()).isEqualTo("tenant-omega");
                verified.set(true);
            });

            assertThat(verified).isTrue();
            assertThat(RequestContext.isBound()).isFalse();
        }

        @Test
        @DisplayName("callWith returns result and propagates exceptions")
        void callWithReturnsResultAndPropagatesException() throws Exception {
            RequestContext ctx = RequestContext.builder()
                    .tenantId("tenant-alpha")
                    .build();

            String result = RequestContext.callWith(ctx, () -> {
                assertThat(RequestContext.isBound()).isTrue();
                return "result-for-" + RequestContext.current().tenantId();
            });

            assertThat(result).isEqualTo("result-for-tenant-alpha");
            assertThat(RequestContext.isBound()).isFalse();

            assertThatThrownBy(() -> RequestContext.callWith(ctx, () -> {
                throw new IllegalArgumentException("Failure inside scope");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("Failure inside scope");
        }

        @Test
        @DisplayName("supports nested context binding with restored outer context")
        void nestedContexts() {
            RequestContext outer = RequestContext.builder().tenantId("outer-tenant").build();
            RequestContext inner = RequestContext.builder().tenantId("inner-tenant").build();

            RequestContext.runWith(outer, () -> {
                assertThat(RequestContext.current().tenantId()).isEqualTo("outer-tenant");

                RequestContext.runWith(inner, () -> {
                    assertThat(RequestContext.current().tenantId()).isEqualTo("inner-tenant");
                });

                assertThat(RequestContext.current().tenantId()).isEqualTo("outer-tenant");
            });

            assertThat(RequestContext.isBound()).isFalse();
        }
    }

    private static final class TestPrincipalContext implements PrincipalContext {
        private final UUID principalId;
        private final Optional<UUID> tenantId;

        TestPrincipalContext(UUID principalId, Optional<UUID> tenantId) {
            this.principalId = principalId;
            this.tenantId = tenantId;
        }

        @Override
        public UUID principalId() {
            return principalId;
        }

        @Override
        public Optional<UUID> tenantId() {
            return tenantId;
        }

        @Override
        public Set<String> roles() {
            return Set.of("admin", "user");
        }

        @Override
        public Set<String> scopes() {
            return Set.of("read", "write");
        }
    }
}
