package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hand-written exchange double that records every {@code respond} attempt, in order, with the overload that
 * was used, and can be made to fail on {@code respond}. Safe to share between threads.
 */
public final class ScriptedExchange implements HttpExchange {

    /** Which {@code respond} overload was called. */
    public enum Kind { RESPONSE, TYPED, STATUS_AND_BODY, STATUS }

    /** One {@code respond} attempt. */
    public record Call(Kind kind, HttpStatus status) { }

    private final HttpRequest request;
    private final List<Call> calls = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public ScriptedExchange(HttpRequest request) {
        this.request = request;
    }

    public static ScriptedExchange get(String path) {
        return new ScriptedExchange(HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of()));
    }

    public static ScriptedExchange of(HttpMethod method, String path, List<HttpHeader> headers) {
        return new ScriptedExchange(HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, headers));
    }

    /** Makes every later {@code respond} attempt throw the given unchecked throwable (after recording it). */
    public ScriptedExchange failOnRespondWith(Throwable throwable) {
        failure.set(throwable);
        return this;
    }

    /** Every {@code respond} attempt, including the ones that threw. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The status of every {@code respond} attempt, in order. */
    public synchronized List<HttpStatus> statuses() {
        List<HttpStatus> statuses = new ArrayList<>();
        for (Call call : calls) {
            statuses.add(call.status());
        }
        return statuses;
    }

    public synchronized int respondCount() {
        return calls.size();
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Map<String, String> pathParams() {
        return Map.of();
    }

    @Override
    public void respond(HttpResponse response) {
        record(Kind.RESPONSE, response.status());
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        record(Kind.TYPED, typedResponse.status());
    }

    @Override
    public void respond(HttpStatus status, Object body) {
        record(Kind.STATUS_AND_BODY, status);
    }

    @Override
    public void respond(HttpStatus status) {
        record(Kind.STATUS, status);
    }

    private void record(Kind kind, HttpStatus status) {
        synchronized (this) {
            calls.add(new Call(kind, status));
        }
        Throwable toThrow = failure.get();
        if (toThrow instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (toThrow instanceof Error error) {
            throw error;
        }
    }
}
