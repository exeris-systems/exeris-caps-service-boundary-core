package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.util.Map;
import java.util.Objects;

/**
 * Decorator around {@link HttpExchange} that attaches the matched route's path parameters.
 */
public final class MatchedHttpExchange implements HttpExchange {

    private final HttpExchange delegate;
    private final Map<String, String> pathParams;

    public MatchedHttpExchange(HttpExchange delegate, Map<String, String> pathParams) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pathParams = pathParams != null ? Map.copyOf(pathParams) : Map.of();
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
        delegate.respond(response);
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        delegate.respond(typedResponse);
    }

    @Override
    public void respond(HttpStatus status, Object body) {
        delegate.respond(status, body);
    }

    @Override
    public void respond(HttpStatus status) {
        delegate.respond(status);
    }
}
