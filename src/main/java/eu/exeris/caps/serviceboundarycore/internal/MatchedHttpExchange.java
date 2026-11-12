package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.Objects;

/**
 * Decorator around {@link HttpExchange} that attaches the matched route's path parameters and records how far
 * a response got.
 *
 * <p>The kernel's exchange claims its response with a compare-and-set before it writes, and refuses a later
 * {@code respond} with {@link IllegalStateException}. A {@code respond} call can therefore throw in two
 * different situations that the caller cannot tell apart: before the claim (argument validation, payload
 * encoding), where a later {@code respond} still succeeds, or after it (the write itself), where a later
 * {@code respond} is refused. This wrapper does not try to tell them apart. It records two facts and lets the
 * registry attempt once.
 *
 * <ul>
 *   <li><b>Answered</b>: a {@code respond} call through this wrapper returned normally. {@link #responded()}
 *       is true exactly then, and never otherwise.</li>
 *   <li><b>Attempted</b>: a {@code respond} call through this wrapper was made, whether it returned or threw.
 *       {@link #attempted()} is true after any call. An exchange that was attempted and not answered is one
 *       whose caller tried to answer and failed.</li>
 * </ul>
 *
 * <p>Both facts live in one volatile field with three states (none, attempted without an answer, answered),
 * so recording an answer costs one volatile write and reading both costs one volatile read. An answer is
 * never downgraded: a refused second call leaves an answered exchange answered. No object is allocated per
 * exchange.
 *
 * <p><b>Invariant: the state is monotonic.</b> The only transitions are none to attempted, none to answered
 * and attempted to answered; nothing leaves answered. Two writers exist. A failure performs
 * {@code compareAndSet(NONE, ATTEMPTED)}, which succeeds only from none and otherwise changes nothing. A
 * success performs a plain volatile write of {@code ANSWERED}, the terminal state. The writes that can race
 * are a failure against a success (and failures against each other). Every interleaving ends answered if any
 * success occurred: a failure's compare-and-set either runs before the success write, in which case it moves
 * none to attempted and the success write then overwrites it with answered, or after it, in which case the
 * field already holds answered, the compare-and-set finds no none and writes nothing. Because the failure
 * path never writes a value it has not just compared against none, it cannot overwrite answered, so no
 * interleaving leaves attempted after a {@code respond} returned. Two failures race harmlessly: both write
 * the same value.
 *
 * <p><b>Readers.</b> {@link #responded()} and {@link #attempted()} are each a single volatile read and
 * return a consistent snapshot of one state. The registry reads after the filter or handler it called has
 * returned, on the thread that called it; the documented handler contract is that a handler answers before
 * it returns. A {@code respond} still in flight on another thread at that moment is outside that contract:
 * the read may then see none or attempted although a success write is about to follow. Seeing attempted is
 * safe, because the registry then ends admission and answers 500 at most once, which the kernel refuses if
 * the exchange was already claimed. Seeing none is the contract violation itself and is not made safe by
 * this class. A reader that sees answered never sees it retracted.
 */
public final class MatchedHttpExchange implements HttpExchange {

    private static final int NONE = 0;
    private static final int ATTEMPTED = 1;
    private static final int ANSWERED = 2;

    private static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(MatchedHttpExchange.class, "state", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final HttpExchange delegate;
    private final Map<String, String> pathParams;
    private volatile int state = NONE;

    public MatchedHttpExchange(HttpExchange delegate, Map<String, String> pathParams) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pathParams = pathParams != null ? Map.copyOf(pathParams) : Map.of();
    }

    /** Reports whether a {@code respond} call through this wrapper returned normally. */
    boolean responded() {
        return state == ANSWERED;
    }

    /**
     * Reports whether any {@code respond} call was made through this wrapper, returned or not. True whenever
     * {@link #responded()} is true.
     */
    boolean attempted() {
        return state != NONE;
    }

    /** Records that a {@code respond} call threw; moves none to attempted and changes nothing else. */
    private void failed() {
        STATE.compareAndSet(this, NONE, ATTEMPTED);
    }

    @Override
    public HttpRequest request() {
        return delegate.request();
    }

    @Override
    public Map<String, String> pathParams() {
        return pathParams;
    }

    @Override
    public void respond(HttpResponse response) {
        try {
            delegate.respond(response);
        } catch (Throwable thrown) {
            failed();
            throw thrown;
        }
        state = ANSWERED;
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        try {
            delegate.respond(typedResponse);
        } catch (Throwable thrown) {
            failed();
            throw thrown;
        }
        state = ANSWERED;
    }

    @Override
    public void respond(HttpStatus status, Object body) {
        try {
            delegate.respond(status, body);
        } catch (Throwable thrown) {
            failed();
            throw thrown;
        }
        state = ANSWERED;
    }

    @Override
    public void respond(HttpStatus status) {
        try {
            delegate.respond(status);
        } catch (Throwable thrown) {
            failed();
            throw thrown;
        }
        state = ANSWERED;
    }
}
