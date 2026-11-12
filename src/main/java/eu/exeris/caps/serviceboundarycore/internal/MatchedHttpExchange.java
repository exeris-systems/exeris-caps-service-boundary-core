package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

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
 * never downgraded: a refused second call leaves an answered exchange answered.
 */
public final class MatchedHttpExchange implements HttpExchange {

    private static final int NONE = 0;
    private static final int ATTEMPTED = 1;
    private static final int ANSWERED = 2;

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

    /** Records that a {@code respond} call threw; an answer already recorded stays. */
    private void failed() {
        if (state != ANSWERED) {
            state = ATTEMPTED;
        }
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
