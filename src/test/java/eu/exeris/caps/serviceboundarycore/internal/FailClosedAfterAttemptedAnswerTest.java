package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.ClaimingExchange;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property that matters most: once a filter has attempted to answer, the request never reaches the
 * handler and never reaches a later filter, however the attempt ended and whatever the filter did next.
 */
@DisplayName("Fail closed: a filter that attempted to answer never lets the request through")
class FailClosedAfterAttemptedAnswerTest {

    private static final String PATH = "/api/thing";

    /** How the filter's respond call ends. */
    enum Attempt { COMPLETES, THROWS_BEFORE_CLAIM, THROWS_AFTER_RECORDING, THROWS_AFTER_CLAIM }

    /** What the filter does with a failed respond. */
    enum Handling { LETS_IT_ESCAPE, SWALLOWS_IT }

    /** What the filter does after its respond call. */
    enum Then { RETURNS_TRUE, RETURNS_FALSE, THROWS_EXCEPTION, THROWS_TENANT_CONFLICT, THROWS_ERROR,
        THROWS_VM_ERROR }

    enum Overload { RESPONSE, TYPED, STATUS_AND_BODY, STATUS }

    enum Entry { HANDLE, EXECUTE }

    static final class Failure extends RuntimeException {
        Failure() {
            super("respond failed");
        }
    }

    static Stream<Arguments> everyCase() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Overload overload : Overload.values()) {
                for (Attempt attempt : Attempt.values()) {
                    for (Handling handling : Handling.values()) {
                        if (attempt == Attempt.COMPLETES && handling == Handling.LETS_IT_ESCAPE) {
                            continue;
                        }
                        for (Then then : Then.values()) {
                            cases.add(Arguments.of(entry, overload, attempt, handling, then));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    private static void respond(HttpExchange exchange, Overload overload) {
        switch (overload) {
            case RESPONSE -> exchange.respond(HttpResponse.noBody(HttpStatus.FORBIDDEN, HttpVersion.HTTP_1_1));
            case TYPED -> exchange.respond(HttpTypedResponse.of(HttpStatus.FORBIDDEN, "denied"));
            case STATUS_AND_BODY -> exchange.respond(HttpStatus.FORBIDDEN, "denied");
            case STATUS -> exchange.respond(HttpStatus.FORBIDDEN);
        }
    }

    private static HttpExchange exchangeFor(Attempt attempt) {
        return switch (attempt) {
            case COMPLETES -> ScriptedExchange.get(PATH);
            case THROWS_BEFORE_CLAIM -> ScriptedExchange.get(PATH)
                    .failFirstRespondBeforeRecordingWith(new Failure());
            case THROWS_AFTER_RECORDING -> ScriptedExchange.get(PATH).failOnRespondWith(new Failure());
            case THROWS_AFTER_CLAIM -> new ClaimingExchange(HttpMethod.GET, PATH, new Failure());
        };
    }

    @ParameterizedTest(name = "{0} {1} {2} {3} {4}")
    @MethodSource("everyCase")
    @DisplayName("neither the next filter nor the handler runs, for every way an attempted answer can end")
    void handlerNeverRuns(Entry entry, Overload overload, Attempt attempt, Handling handling, Then then) {
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        AtomicInteger handlerRuns = new AtomicInteger();
        CountingFilter later = new CountingFilter("zz-later", AdmissionPhase.POST_ADMISSION, (ex, m) -> true);
        CountingFilter subject = new CountingFilter("aa-subject", AdmissionPhase.AUTHENTICATION, (ex, m) -> {
            try {
                respond(ex, overload);
            } catch (RuntimeException failed) {
                if (handling == Handling.LETS_IT_ESCAPE) {
                    throw failed;
                }
            }
            switch (then) {
                case RETURNS_TRUE -> { return true; }
                case RETURNS_FALSE -> { return false; }
                case THROWS_EXCEPTION -> throw new IllegalArgumentException("after");
                case THROWS_TENANT_CONFLICT -> throw new TenantConflictException();
                case THROWS_ERROR -> throw new Probes.CustomError("after");
                case THROWS_VM_ERROR -> throw new Probes.CustomVmError("after");
            }
            return true;
        });
        registry.registerFilter(subject);
        registry.registerFilter(later);
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                List.of(RouteRequirement.permitAll()), ex -> {
                    handlerRuns.incrementAndGet();
                    ex.respond(HttpStatus.OK);
                }));
        HttpExchange exchange = exchangeFor(attempt);

        Boolean proceed = null;
        try (LogCapture ignored = new LogCapture(DefaultApiSurfaceRegistry.class)) {
            try {
                if (entry == Entry.HANDLE) {
                    registry.handle(exchange);
                } else {
                    RouteMatch match = registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
                    proceed = registry.execute(exchange, match);
                }
            } catch (Probes.CustomError | Probes.CustomVmError propagated) {
                // an Error being handled leaves the call; the request is still over
            }
        }

        assertThat(subject.calls).as("the subject filter ran once").hasValue(1);
        assertThat(later.calls).as("the later filter").hasValue(0);
        assertThat(handlerRuns).as("the handler").hasValue(0);
        if (entry == Entry.EXECUTE && proceed != null) {
            assertThat(proceed).as("execute lets the request through").isFalse();
        }
    }
}
