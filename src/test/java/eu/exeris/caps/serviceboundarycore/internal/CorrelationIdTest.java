package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RequestContext;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The correlation id, generated when first read")
class CorrelationIdTest {

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The three views one request produces: a filter's, a later filter's and the handler's. */
    private record Views(RequestContext first, RequestContext second, RequestContext handler) { }

    private static Views request(boolean[] readDuringRequest, String hostId) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        RequestContext[] views = new RequestContext[3];
        registry.registerFilter(Fixtures.filter("a", AdmissionPhase.PRE_ADMISSION, m -> {
            views[0] = RequestContext.current();
            if (readDuringRequest[0]) {
                views[0].correlationId();
            }
            AdmissionContext.current().attribute("k", "v");
        }));
        registry.registerFilter(Fixtures.filter("b", AdmissionPhase.AUTHENTICATION, m -> {
            views[1] = RequestContext.current();
            if (readDuringRequest[1]) {
                views[1].correlationId();
            }
            AdmissionContext.current().authenticate(Fixtures.person(1));
        }));
        registry.registerRoute(Fixtures.open("/p", ex -> {
            views[2] = RequestContext.current();
            if (readDuringRequest[2]) {
                views[2].correlationId();
            }
            ex.respond(HttpStatus.OK);
        }));
        ScriptedExchange exchange = ScriptedExchange.get("/p");
        if (hostId == null) {
            registry.handle(exchange);
        } else {
            RequestContext.runWith(RequestContext.builder().correlationId(hostId).build(), () -> registry.handle(exchange));
        }
        assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        return new Views(views[0], views[1], views[2]);
    }

    private static final int[][] PERMUTATIONS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    @Test
    @DisplayName("within a request every view returns one id, whichever is read first, during or after the request")
    void oneIdWhicheverIsReadFirst() {
        for (int mask = 0; mask < 8; mask++) {
            boolean[] during = {(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0};
            for (int[] order : PERMUTATIONS) {
                Views views = request(during, null);
                RequestContext[] all = {views.first(), views.second(), views.handler()};
                assertThat(all[0]).isNotSameAs(all[1]);
                assertThat(all[1]).isNotSameAs(all[2]);
                String expected = all[order[0]].correlationId();
                for (int index : order) {
                    assertThat(all[index].correlationId()).as("mask %d order %s view %d", mask, List.of(order[0], order[1], order[2]), index)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    @DisplayName("a host-bound id is returned by every derived context")
    void hostIdReachesEveryView() {
        Views views = request(new boolean[] {false, false, false}, "host-id-7");
        assertThat(views.first().correlationId()).isEqualTo("host-id-7");
        assertThat(views.second().correlationId()).isEqualTo("host-id-7");
        assertThat(views.handler().correlationId()).isEqualTo("host-id-7");
    }

    @Test
    @DisplayName("an unread host-bound generated id is shared with the request's views when first read through a derived one")
    void hostWithoutIdGeneratesOnceForTheWholeRequest() {
        RequestContext host = RequestContext.builder().tenantId("t").build();
        RequestContext.runWith(host, () -> {
            DefaultAdmissionContext admission = DefaultAdmissionContext.open();
            RequestContext derived = admission.requestContext();
            admission.attribute("k", "v");
            RequestContext later = admission.requestContext();
            String fromLater = later.correlationId();
            assertThat(derived.correlationId()).isEqualTo(fromLater);
            assertThat(host.correlationId()).isEqualTo(fromLater);
        });
    }

    @Test
    @DisplayName("the first read from 8 racing threads on a fresh context yields one identical value")
    void racingFirstRead() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 2500; round++) {
                RequestContext fresh = RequestContext.builder().build();
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<String>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return fresh.correlationId();
                    }));
                }
                Set<String> seen = new HashSet<>();
                for (Future<String> future : futures) {
                    seen.add(future.get(1, TimeUnit.MINUTES));
                }
                assertThat(seen).as("round %d", round).hasSize(1);
                assertThat(fresh.correlationId()).isEqualTo(seen.iterator().next());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("racing first reads through different derived contexts of one request yield one identical value")
    void racingFirstReadThroughDerivedContexts() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 2500; round++) {
                DefaultAdmissionContext admission = DefaultAdmissionContext.open();
                RequestContext[] views = new RequestContext[4];
                for (int i = 0; i < views.length; i++) {
                    views[i] = admission.requestContext();
                    admission.attribute("k", i);
                }
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<String>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    RequestContext mine = views[t % views.length];
                    futures.add(pool.submit(() -> {
                        start.await();
                        return mine.correlationId();
                    }));
                }
                Set<String> seen = new HashSet<>();
                for (Future<String> future : futures) {
                    seen.add(future.get(1, TimeUnit.MINUTES));
                }
                assertThat(seen).as("round %d", round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an id is stable across reads, differs between requests and is a 36-character UUID string")
    void stableDistinctAndFormatted() {
        RequestContext context = RequestContext.builder().build();
        String id = context.correlationId();
        for (int i = 0; i < 100; i++) {
            assertThat(context.correlationId()).isEqualTo(id);
        }
        assertThat(id).hasSize(36).matches(UUID_TEXT);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        Views one = request(new boolean[] {true, true, true}, null);
        Views two = request(new boolean[] {true, true, true}, null);
        assertThat(one.handler().correlationId()).isNotEqualTo(two.handler().correlationId());
    }

    @Test
    @DisplayName("an explicit id is returned verbatim; a blank one is replaced by a generated id on first read")
    void explicitAndBlank() {
        for (String explicit : new String[] {"abc", "  padded  ", "x".repeat(300), "ü-ż", "00000000-0000-0000-0000-000000000000"}) {
            RequestContext context = RequestContext.builder().correlationId(explicit).build();
            assertThat(context.correlationId()).isEqualTo(explicit).isSameAs(context.correlationId());
        }
        for (String blank : new String[] {"", " ", "   ", "\t", "\n \t"}) {
            RequestContext context = RequestContext.builder().correlationId(blank).build();
            String id = context.correlationId();
            assertThat(id).matches(UUID_TEXT);
            assertThat(context.correlationId()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("equals, hashCode and toString read the id: contexts of one request with equal state are equal, others are not")
    void equalsHashCodeToString() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext first = admission.requestContext();
        admission.attribute("k", "v");
        RequestContext second = admission.requestContext();
        admission.attribute("k", "v");
        RequestContext third = admission.requestContext();
        assertThat(first).isNotEqualTo(second);
        assertThat(second).isEqualTo(third).hasSameHashCodeAs(third);
        assertThat(second.toString()).isEqualTo(third.toString()).contains(first.correlationId());

        RequestContext a = RequestContext.builder().build();
        RequestContext b = RequestContext.builder().build();
        assertThat(a).as("two independently built contexts draw distinct ids once compared").isNotEqualTo(b);
        assertThat(a.correlationId()).isNotEqualTo(b.correlationId());

        RequestContext printed = RequestContext.builder().build();
        String text = printed.toString();
        assertThat(text).contains(printed.correlationId());
        RequestContext hashed = RequestContext.builder().build();
        int hash = hashed.hashCode();
        assertThat(hashed.hashCode()).isEqualTo(hash);
        RequestContext explicit1 = RequestContext.builder().correlationId("same").tenantId("t").build();
        RequestContext explicit2 = RequestContext.builder().correlationId("same").tenantId("t").build();
        assertThat(explicit1).isEqualTo(explicit2).hasSameHashCodeAs(explicit2);
    }

    @Test
    @DisplayName("toString names the principal id only and leaks no credential, role or scope")
    void toStringLeaksNothing() {
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        Fixtures.Credentialed principal = new Fixtures.Credentialed(new UUID(7, 7), "TOP-SECRET-TOKEN");
        admission.authenticate(principal, UUID.randomUUID());
        admission.attribute("password", "hunter2-attribute");
        String text = admission.requestContext().toString();
        assertThat(text).contains(new UUID(7, 7).toString());
        assertThat(text).doesNotContain("TOP-SECRET-TOKEN").doesNotContain("hunter2-attribute").doesNotContain("scope-")
                .doesNotContain("role-");
    }

    @Test
    @DisplayName("a context nobody reads holds no generated id; one read through a derived context fixes the source's too")
    void nothingIsGeneratedUntilRead() throws Exception {
        Field field = DefaultRequestContext.class.getDeclaredField("generatedCorrelationId");
        field.setAccessible(true);
        DefaultAdmissionContext admission = DefaultAdmissionContext.open();
        RequestContext first = admission.requestContext();
        admission.attribute("k", "v");
        RequestContext second = admission.requestContext();
        assertThat(field.get(first)).isNull();
        assertThat(field.get(second)).isNull();
        String id = second.correlationId();
        assertThat(field.get(second)).isEqualTo(id);
        assertThat(field.get(first)).as("the source drew the id the derived context returns").isEqualTo(id);

        Views unread = request(new boolean[] {false, false, false}, null);
        assertThat(field.get(unread.first())).isNull();
        assertThat(field.get(unread.second())).isNull();
        assertThat(field.get(unread.handler())).isNull();
        RequestContext built = RequestContext.builder().build();
        assertThat(field.get(built)).isNull();
        built.correlationId();
        assertThat(field.get(built)).isNotNull();
    }

    @Test
    @DisplayName("ids are version 4, variant 2, and 10000 of them are pairwise distinct")
    void idsAreRandomUuids() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = RequestContext.builder().build().correlationId();
            UUID parsed = UUID.fromString(id);
            assertThat(parsed.version()).isEqualTo(4);
            assertThat(parsed.variant()).isEqualTo(2);
            assertThat(seen.add(id)).as("duplicate id %s", id).isTrue();
        }
    }

    @Test
    @DisplayName("the id path of the production classes uses UUID.randomUUID() and no predictable source")
    void idPathUsesNoPredictableSource() throws IOException {
        for (String file : new String[] {"DefaultRequestContext", "DefaultAdmissionContext"}) {
            String source = Files.readString(Path.of("src/main/java/eu/exeris/caps/serviceboundarycore/internal/"
                    + file + ".java"));
            String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            for (String forbidden : new String[] {"ThreadLocalRandom", "Random(", "SplittableRandom", "AtomicLong",
                    "AtomicInteger", "LongAdder", "nanoTime", "currentTimeMillis", "Math.random", "Instant.now",
                    "nameUUIDFromBytes", "new UUID("}) {
                assertThat(code).as("%s must not contain %s", file, forbidden).doesNotContain(forbidden);
            }
        }
        String generator = Files.readString(Path.of(
                "src/main/java/eu/exeris/caps/serviceboundarycore/internal/DefaultRequestContext.java"));
        assertThat(generator).contains("UUID.randomUUID().toString()");
        assertThat(generator.split("randomUUID\\(\\)", -1).length - 1).as("one generation site, outside comments").isGreaterThanOrEqualTo(1);
    }
}
