package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exchange double for a kernel that claimed its response and then failed while writing it: the first
 * {@code respond} attempt throws the given failure and records nothing visible, and every later attempt is
 * refused with {@link IllegalStateException}. Every attempt, refused or not, is counted.
 */
public final class ClaimingExchange implements HttpExchange {

    private final HttpRequest request;
    private final RuntimeException firstFailure;
    private final List<HttpStatus> attempts = new ArrayList<>();

    public ClaimingExchange(HttpMethod method, String path, RuntimeException firstFailure) {
        this.request = HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, List.of());
        this.firstFailure = firstFailure;
    }

    /** The status of every {@code respond} attempt, in order. */
    public synchronized List<HttpStatus> attempts() {
        return List.copyOf(attempts);
    }

    @Override public HttpRequest request() { return request; }

    @Override public Map<String, String> pathParams() { return Map.of(); }

    @Override public void respond(HttpResponse response) { attempt(response.status()); }

    @Override public void respond(HttpTypedResponse typedResponse) { attempt(typedResponse.status()); }

    @Override public void respond(HttpStatus status, Object body) { attempt(status); }

    @Override public void respond(HttpStatus status) { attempt(status); }

    private synchronized void attempt(HttpStatus status) {
        boolean first = attempts.isEmpty();
        attempts.add(status);
        if (first) {
            throw firstFailure;
        }
        throw new IllegalStateException("The exchange has already been answered");
    }
}
