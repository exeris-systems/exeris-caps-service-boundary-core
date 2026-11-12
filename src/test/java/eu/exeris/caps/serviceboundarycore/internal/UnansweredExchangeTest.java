package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.AdmissionContext;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.caps.serviceboundarycore.testsupport.LogCapture;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes;
import eu.exeris.caps.serviceboundarycore.testsupport.Probes.CountingFilter;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("No matched exchange is left unanswered")
class UnansweredExchangeTest {

    private static final String PATH = "/api/thing";

    private DefaultApiSurfaceRegistry registry;
    private AtomicInteger handlerRuns;

    @BeforeEach
    void setUp() {
        registry = new DefaultApiSurfaceRegistry();
        handlerRuns = new AtomicInteger();
    }

    private void route(HttpHandler handler) {
        route(List.of(RouteRequirement.permitAll()), handler);
    }

    private void route(List<RouteRequirement> requirements, HttpHandler handler) {
        registry.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "Thing#get", requirements, ex -> {
            handlerRuns.incrementAndGet();
            handler.handle(ex);
        }));
    }

    private CountingFilter filter(String name, Probes.FilterBody body) {
        CountingFilter filter = new CountingFilter(name, AdmissionPhase.AUTHENTICATION, body);
        registry.registerFilter(filter);
        return filter;
    }

    private ScriptedExchange handle() {
        ScriptedExchange exchange = ScriptedExchange.get(PATH);
        registry.handle(exchange);
        return exchange;
    }

    @Nested
    @DisplayName("a filter that returns false")
    class FilterReturnsFalse {

        @Test
        @DisplayName("without responding is answered 500 exactly once, with a bare status, and the handler does not run")
        void unansweredFalseIsAnswered500() {
            filter("halts-silently", (ex, m) -> false);
            route(ex -> ex.respond(HttpStatus.OK));

            ScriptedExchange exchange = handle();

            assertThat(exchange.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("after responding keeps its own answer and is never followed by a 500")
        void answeredFalseKeepsItsAnswer() {
            filter("denies", (ex, m) -> {
                ex.respond(HttpStatus.TOO_MANY_REQUESTS);
                return false;
            });
            route(ex -> ex.respond(HttpStatus.OK));

            ScriptedExchange exchange = handle();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("stops the later filters")
        void laterFiltersDoNotRun() {
            filter("a-halts", (ex, m) -> false);
            CountingFilter later = filter("b-later", (ex, m) -> true);
            route(ex -> ex.respond(HttpStatus.OK));

            handle();

            assertThat(later.calls).hasValue(0);
        }
    }

    @Nested
    @DisplayName("a filter that responds and returns true (outside the contract)")
    class FilterRespondsAndProceeds {

        @Test
        @DisplayName("ends admission: the handler does not run and the filter's answer is the only one")
        void handlerDoesNotRunAfterAFilterAnswer() {
            filter("answers-then-proceeds", (ex, m) -> {
                ex.respond(HttpStatus.ACCEPTED);
                return true;
            });
            route(ex -> ex.respond(HttpStatus.OK));

            ScriptedExchange exchange = handle();

            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
        }

        @Test
        @DisplayName("adds no 500 even for a handler that would not answer, because that handler never runs")
        void silentHandlerIsNotReachedAfterFilterAnswer() {
            filter("answers-then-proceeds", (ex, m) -> {
                ex.respond(HttpStatus.ACCEPTED);
                return true;
            });
            route(ex -> { });

            ScriptedExchange exchange = handle();

            assertThat(handlerRuns).hasValue(0);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("a handler")
    class Handler {

        @Test
        @DisplayName("that returns without responding is answered 500 exactly once, with a bare status")
        void silentHandlerAnswered500() {
            route(ex -> { });

            ScriptedExchange exchange = handle();

            assertThat(exchange.calls()).containsExactly(
                    new ScriptedExchange.Call(ScriptedExchange.Kind.STATUS, HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(handlerRuns).hasValue(1);
        }

        @Test
        @DisplayName("that responds once is answered by nobody else")
        void answeringHandlerIsNotAnsweredAgain() {
            route(ex -> ex.respond(HttpStatus.CREATED));

            assertThat(handle().statuses()).containsExactly(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("answers through every respond overload and each counts as answered")
        void everyOverloadCountsAsAnswered() {
            for (int overload = 0; overload < 4; overload++) {
                final int chosen = overload;
                DefaultApiSurfaceRegistry local = new DefaultApiSurfaceRegistry();
                local.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                        List.of(RouteRequirement.permitAll()), ex -> {
                            switch (chosen) {
                                case 0 -> ex.respond(HttpStatus.OK);
                                case 1 -> ex.respond(HttpStatus.OK, "body");
                                case 2 -> ex.respond(new eu.exeris.kernel.spi.http.HttpResponse(HttpStatus.OK,
                                        eu.exeris.kernel.spi.http.HttpVersion.HTTP_1_1, List.of(), null));
                                default -> ex.respond(HttpStatus.OK, (Object) null);
                            }
                        }));
                ScriptedExchange exchange = ScriptedExchange.get(PATH);

                local.handle(exchange);

                assertThat(exchange.statuses()).as("overload %d", chosen).containsExactly(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("that responds twice is not answered a third time and the registry adds nothing")
        void doubleRespondAddsNothing() {
            route(ex -> {
                ex.respond(HttpStatus.OK);
                ex.respond(HttpStatus.OK);
            });

            assertThat(handle().statuses()).containsExactly(HttpStatus.OK, HttpStatus.OK);
        }

        @Test
        @DisplayName("that responds on another thread and waits for it before returning is not answered 500")
        void answerFromAnotherThreadBeforeReturnCounts() {
            route(ex -> {
                Thread other = Thread.ofVirtual().unstarted(() -> ex.respond(HttpStatus.OK));
                other.start();
                try {
                    other.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });

            assertThat(handle().statuses()).containsExactly(HttpStatus.OK);
        }

        @Test
        @DisplayName("that hands the answer to another thread and returns first is answered 500, and a later answer is outside the contract")
        void answerAfterReturnIsTooLate() throws Exception {
            AtomicReference<Thread> deferred = new AtomicReference<>();
            java.util.concurrent.CountDownLatch mayAnswer = new java.util.concurrent.CountDownLatch(1);
            route(ex -> deferred.set(Thread.ofVirtual().start(() -> {
                try {
                    mayAnswer.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ex.respond(HttpStatus.OK);
            })));

            ScriptedExchange exchange = handle();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);

            mayAnswer.countDown();
            deferred.get().join();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("an Exception")
    class ExceptionCases {

        @Test
        @DisplayName("thrown by a filter before any answer is answered 500 once and the handler does not run")
        void filterException() {
            filter("boom", (ex, m) -> {
                throw new IllegalStateException("boom");
            });
            route(ex -> ex.respond(HttpStatus.OK));

            ScriptedExchange exchange = handle();

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("thrown by a handler before any answer is answered 500 once")
        void handlerException() {
            route(ex -> {
                throw new IllegalStateException("boom");
            });

            assertThat(handle().statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("thrown by a filter after it answered adds no second answer, does not reach the caller, and stops the pipeline")
        void filterAnswersThenThrowsException() {
            filter("answers-then-fails", (ex, m) -> {
                ex.respond(HttpStatus.FORBIDDEN);
                throw new IllegalStateException("boom");
            });
            route(ex -> ex.respond(HttpStatus.OK));

            ScriptedExchange[] holder = new ScriptedExchange[1];
            assertThatCode(() -> holder[0] = handle()).doesNotThrowAnyException();

            assertThat(holder[0].statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("thrown by a handler after it answered adds no second answer and does not reach the caller")
        void handlerAnswersThenThrowsException() {
            route(ex -> {
                ex.respond(HttpStatus.OK);
                throw new IllegalStateException("boom");
            });

            ScriptedExchange[] holder = new ScriptedExchange[1];
            assertThatCode(() -> holder[0] = handle()).doesNotThrowAnyException();

            assertThat(holder[0].statuses()).containsExactly(HttpStatus.OK);
        }

        @Test
        @DisplayName("TenantConflictException from a filter is answered 403, not 500")
        void tenantConflictFromFilter() {
            filter("conflict", (ex, m) -> {
                throw new TenantConflictException();
            });
            route(ex -> ex.respond(HttpStatus.OK));

            assertThat(handle().statuses()).containsExactly(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("TenantConflictException from a handler is answered 403, not 500")
        void tenantConflictFromHandler() {
            route(ex -> {
                throw new TenantConflictException();
            });

            assertThat(handle().statuses()).containsExactly(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("when answering 500 itself throws, handle() returns normally and the failure is logged once at ERROR by class name")
        void respondFailureAfterExceptionIsLoggedNotThrown() {
            route(ex -> {
                throw new IllegalStateException("boom");
            });
            IllegalArgumentException respondFailure = new IllegalArgumentException("respond failed");
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(respondFailure);

            try (LogCapture log = new LogCapture(DefaultApiSurfaceRegistry.class)) {
                assertThatCode(() -> registry.handle(exchange)).doesNotThrowAnyException();

                assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
                List<LogRecord> answering = log.at(Level.SEVERE).stream()
                        .filter(r -> LogCapture.everything(r).contains(IllegalArgumentException.class.getName()))
                        .toList();
                assertThat(answering).hasSize(1);
                assertThat(answering.get(0).getThrown()).isNull();
                assertThat(LogCapture.everything(answering.get(0))).doesNotContain("respond failed");
            }
        }
    }

    @Nested
    @DisplayName("an Error")
    class ErrorCases {

        @Test
        @DisplayName("thrown by a filter before any answer is answered 500 once and then the same Error is rethrown")
        void filterError() {
            Probes.CustomError error = new Probes.CustomError("filter error");
            filter("errs", (ex, m) -> {
                throw error;
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handlerRuns).hasValue(0);
            assertThat(error.getSuppressed()).isEmpty();
        }

        @Test
        @DisplayName("thrown by a handler before any answer is answered 500 once and then the same Error is rethrown")
        void handlerError() {
            Probes.CustomError error = new Probes.CustomError("handler error");
            route(ex -> {
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(error.getSuppressed()).isEmpty();
        }

        @Test
        @DisplayName("a JDK Error that is not a VirtualMachineError, such as LinkageError, is handled like any Error")
        void jdkErrorsBehaveAsErrors() {
            LinkageError error = new LinkageError("x");
            route(ex -> {
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("thrown by a filter after it answered is rethrown without a second answer")
        void filterErrorAfterAnswer() {
            Probes.CustomError error = new Probes.CustomError("late");
            filter("answers-then-errs", (ex, m) -> {
                ex.respond(HttpStatus.FORBIDDEN);
                throw error;
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("thrown by a handler after it answered is rethrown without a second answer")
        void handlerErrorAfterAnswer() {
            Probes.CustomError error = new Probes.CustomError("late");
            route(ex -> {
                ex.respond(HttpStatus.OK);
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        }

        @Test
        @DisplayName("when answering 500 throws a RuntimeException, the original Error is rethrown carrying it as suppressed (filter)")
        void filterErrorWithFailingRespond() {
            Probes.CustomError error = new Probes.CustomError("original");
            IllegalStateException respondFailure = new IllegalStateException("respond failed");
            filter("errs", (ex, m) -> {
                throw error;
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(respondFailure);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(error.getSuppressed()).containsExactly(respondFailure);
            assertThat(exchange.respondCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("when answering 500 throws a RuntimeException, the original Error is rethrown carrying it as suppressed (handler)")
        void handlerErrorWithFailingRespond() {
            Probes.CustomError error = new Probes.CustomError("original");
            IllegalStateException respondFailure = new IllegalStateException("respond failed");
            route(ex -> {
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(respondFailure);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(error.getSuppressed()).containsExactly(respondFailure);
            assertThat(exchange.respondCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("when answering 500 throws another Error, an Error still leaves handle() (observed: the original is not attached)")
        void errorWhileAnsweringAnError() {
            Probes.CustomError original = new Probes.CustomError("original");
            Probes.CustomError answerFailure = new Probes.CustomError("respond failed");
            route(ex -> {
                throw original;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(answerFailure);

            assertThatThrownBy(() -> registry.handle(exchange)).isInstanceOf(Probes.CustomError.class);
        }
    }

    @Nested
    @DisplayName("a VirtualMachineError")
    class VirtualMachineErrors {

        @Test
        @DisplayName("from a filter is rethrown with no respond at all (custom subclass)")
        void filterCustomVmError() {
            Probes.CustomVmError error = new Probes.CustomVmError("vm");
            filter("vm", (ex, m) -> {
                throw error;
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.respondCount()).isZero();
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("from a filter is rethrown with no respond at all (InternalError)")
        void filterInternalError() {
            InternalError error = new InternalError("vm");
            filter("vm", (ex, m) -> {
                throw error;
            });
            route(ex -> ex.respond(HttpStatus.OK));
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.respondCount()).isZero();
        }

        @Test
        @DisplayName("from a handler is rethrown with no respond at all (custom subclass)")
        void handlerCustomVmError() {
            Probes.CustomVmError error = new Probes.CustomVmError("vm");
            route(ex -> {
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.respondCount()).isZero();
        }

        @Test
        @DisplayName("from a handler is rethrown with no respond at all (InternalError, OutOfMemoryError, StackOverflowError)")
        void handlerJdkVmErrors() {
            for (Error error : new Error[] {new InternalError("a"), new OutOfMemoryError("b"), new StackOverflowError("c")}) {
                DefaultApiSurfaceRegistry local = new DefaultApiSurfaceRegistry();
                local.registerRoute(new ApiSurfaceRoute(HttpMethod.GET, PATH, "T#t",
                        List.of(RouteRequirement.permitAll()), ex -> {
                            throw error;
                        }));
                ScriptedExchange exchange = ScriptedExchange.get(PATH);

                assertThatThrownBy(() -> local.handle(exchange)).isSameAs(error);

                assertThat(exchange.respondCount()).as(error.getClass().getSimpleName()).isZero();
            }
        }

        @Test
        @DisplayName("after the handler answered is rethrown with no second answer")
        void vmErrorAfterAnswer() {
            Probes.CustomVmError error = new Probes.CustomVmError("vm");
            route(ex -> {
                ex.respond(HttpStatus.OK);
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.OK);
        }

        @Test
        @DisplayName("is rethrown untouched when respond would fail: nothing is attached to it")
        void vmErrorNotTouchedByFailingRespond() {
            Probes.CustomVmError error = new Probes.CustomVmError("vm");
            route(ex -> {
                throw error;
            });
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(new IllegalStateException("no"));

            assertThatThrownBy(() -> registry.handle(exchange)).isSameAs(error);

            assertThat(error.getSuppressed()).isEmpty();
            assertThat(exchange.respondCount()).isZero();
        }
    }

    @Nested
    @DisplayName("answers that are not a 500 stay exactly one answer")
    class OrdinaryAnswersStayUnchanged {

        @Test
        @DisplayName("401 from a route requirement")
        void unauthorized() {
            route(List.of(RouteRequirement.authenticated()), ex -> ex.respond(HttpStatus.OK));

            assertThat(handle().statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("403 from a route requirement")
        void forbidden() {
            filter("auth", (ex, m) -> {
                AdmissionContext.current().authenticate(new Probes.Principal(java.util.Set.of("r")));
                return true;
            });
            route(List.of(RouteRequirement.requiringAllScopes(java.util.Set.of("w"))), ex -> ex.respond(HttpStatus.OK));

            assertThat(handle().statuses()).containsExactly(HttpStatus.FORBIDDEN);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("503 during teardown, with a filter that would halt silently registered")
        void teardown() {
            filter("halts-silently", (ex, m) -> false);
            route(ex -> { });
            registry.beginTeardown();

            assertThat(handle().statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("404 for an unmatched path")
        void notFound() {
            route(ex -> { });
            ScriptedExchange exchange = ScriptedExchange.get("/nothing");

            registry.handle(exchange);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("405 for a matching path under another method")
        void methodNotAllowed() {
            route(ex -> { });
            ScriptedExchange exchange = ScriptedExchange.of(HttpMethod.POST, PATH, List.of());

            registry.handle(exchange);

            assertThat(exchange.statuses()).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("through RequestFilterChain.execute")
    class ThroughExecute {

        private RouteMatch match() {
            route(ex -> ex.respond(HttpStatus.OK));
            return registry.matchRoute(HttpMethod.GET, PATH).orElseThrow();
        }

        @Test
        @DisplayName("an unanswered false is answered 500 once and execute returns false")
        void unansweredFalse() {
            filter("halts-silently", (ex, m) -> false);
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            boolean proceed = registry.execute(exchange, match);

            assertThat(proceed).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("an answered false keeps its answer and execute returns false")
        void answeredFalse() {
            filter("denies", (ex, m) -> {
                ex.respond(HttpStatus.UNAUTHORIZED);
                return false;
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("when every filter passes execute returns true and the host answers: nothing is answered yet")
        void passingChainAnswersNothing() {
            filter("passes", (ex, m) -> true);
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isTrue();
            assertThat(exchange.respondCount()).isZero();
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("an Exception from a filter is answered 500 and execute returns false")
        void exceptionFromFilter() {
            filter("boom", (ex, m) -> {
                throw new IllegalStateException("boom");
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("an Exception after an answer adds nothing")
        void exceptionAfterAnswer() {
            filter("answers-then-fails", (ex, m) -> {
                ex.respond(HttpStatus.FORBIDDEN);
                throw new IllegalStateException("boom");
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("an Error from a filter is answered 500 and the same Error is rethrown")
        void errorFromFilter() {
            Probes.CustomError error = new Probes.CustomError("e");
            filter("errs", (ex, m) -> {
                throw error;
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.execute(exchange, match)).isSameAs(error);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("an Error after an answer is rethrown without a second answer")
        void errorAfterAnswer() {
            Probes.CustomError error = new Probes.CustomError("e");
            filter("answers-then-errs", (ex, m) -> {
                ex.respond(HttpStatus.FORBIDDEN);
                throw error;
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.execute(exchange, match)).isSameAs(error);
            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a VirtualMachineError is rethrown with no respond")
        void vmErrorFromFilter() {
            Probes.CustomVmError error = new Probes.CustomVmError("vm");
            filter("vm", (ex, m) -> {
                throw error;
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThatThrownBy(() -> registry.execute(exchange, match)).isSameAs(error);
            assertThat(exchange.respondCount()).isZero();
        }

        @Test
        @DisplayName("a failing respond while answering an Error is attached as suppressed")
        void failingRespondIsSuppressed() {
            Probes.CustomError error = new Probes.CustomError("e");
            IllegalStateException respondFailure = new IllegalStateException("respond failed");
            filter("errs", (ex, m) -> {
                throw error;
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH).failOnRespondWith(respondFailure);

            assertThatThrownBy(() -> registry.execute(exchange, match)).isSameAs(error);
            assertThat(error.getSuppressed()).containsExactly(respondFailure);
        }

        @Test
        @DisplayName("TenantConflictException from a filter is answered 403")
        void tenantConflict() {
            filter("conflict", (ex, m) -> {
                throw new TenantConflictException();
            });
            RouteMatch match = match();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("teardown is answered 503, once, and execute returns false")
        void teardown() {
            filter("halts-silently", (ex, m) -> false);
            RouteMatch match = match();
            registry.beginTeardown();
            ScriptedExchange exchange = ScriptedExchange.get(PATH);

            assertThat(registry.execute(exchange, match)).isFalse();
            assertThat(exchange.statuses()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("an exchange that is already a MatchedHttpExchange is answered through that same exchange")
        void matchedExchangeIsReused() {
            filter("halts-silently", (ex, m) -> false);
            RouteMatch match = match();
            ScriptedExchange inner = ScriptedExchange.get(PATH);
            MatchedHttpExchange tracked = new MatchedHttpExchange(inner, match.pathParams());

            assertThat(registry.execute(tracked, match)).isFalse();

            assertThat(tracked.responded()).isTrue();
            assertThat(inner.statuses()).containsExactly(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
