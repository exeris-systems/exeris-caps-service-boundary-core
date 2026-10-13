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

            assertThat(ctx.tenantId()).isEmpty();
            assertThat(ctx.sessionId()).isEmpty();
            assertThat(ctx.correlationId()).isNotBlank();
            assertThat(ctx.principal()).isEmpty();
            assertThat(ctx.attributes()).isEmpty();
        }

        @Test
        @DisplayName("builds with custom tenant and attributes")
        void buildsWithCustomValues() {
            RequestContext ctx = RequestContext.builder()
                    .tenantId("tenant-acme")
                    .correlationId("corr-12345")
                    .attribute("client-ip", "192.168.1.1")
                    .attribute("trace-sampled", true)
                    .build();

            assertThat(ctx.tenantId()).contains("tenant-acme");
            assertThat(ctx.correlationId()).isEqualTo("corr-12345");
            assertThat(ctx.principal()).isEmpty();
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
                assertThat(RequestContext.current().tenantId()).contains("tenant-omega");
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
                return "result-for-" + RequestContext.current().tenantId().orElseThrow();
            });

            assertThat(result).isEqualTo("result-for-tenant-alpha");
            assertThat(RequestContext.isBound()).isFalse();

            assertThatThrownBy(() -> RequestContext.callWith(ctx, () -> {
                throw new IllegalArgumentException("Failure inside scope");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("Failure inside scope");
        }

        @Test
        @DisplayName("supports nested binding of an equivalent context with restored outer context")
        void nestedContexts() {
            RequestContext outer = RequestContext.builder().tenantId("outer-tenant").correlationId("outer").build();
            RequestContext sameTenant = RequestContext.builder().tenantId("outer-tenant").correlationId("inner").build();

            RequestContext.runWith(outer, () -> {
                RequestContext.runWith(sameTenant, () -> {
                    assertThat(RequestContext.current().correlationId()).isEqualTo("inner");
                });

                assertThat(RequestContext.current().correlationId()).isEqualTo("outer");
            });

            assertThat(RequestContext.isBound()).isFalse();
        }

        @Test
        @DisplayName("a bound context cannot be replaced by one with another tenant")
        void nestedContextCannotChangeTenant() {
            RequestContext outer = RequestContext.builder().tenantId("outer-tenant").build();
            RequestContext other = RequestContext.builder().tenantId("inner-tenant").build();

            RequestContext.runWith(outer, () ->
                    assertThatThrownBy(() -> RequestContext.runWith(other, () -> { }))
                            .isInstanceOf(IllegalStateException.class));
        }

        @Test
        @DisplayName("a context implemented outside this capability cannot be bound")
        void foreignImplementationRefused() {
            RequestContext foreign = new RequestContext() {
                @Override public Optional<String> tenantId() { return Optional.empty(); }
                @Override public String correlationId() { return "x"; }
                @Override public Optional<PrincipalContext> principal() {
                    return Optional.of(new TestPrincipalContext(UUID.randomUUID(), Optional.empty()));
                }
                @Override public Optional<UUID> sessionId() { return Optional.empty(); }
                @Override public Map<String, Object> attributes() { return Map.of(); }
                @Override public Optional<Object> attribute(String key) { return Optional.empty(); }
            };

            assertThatThrownBy(() -> RequestContext.runWith(foreign, () -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
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
