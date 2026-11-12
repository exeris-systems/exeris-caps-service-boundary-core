package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A route handler that counts every call the registry could make on it. {@code equals}, {@code hashCode} and
 * {@code toString} are identity-based and counted; {@code handle} answers 200 and is counted.
 */
public final class CountingHandler implements HttpHandler {

    public final AtomicInteger handleCalls = new AtomicInteger();
    public final AtomicInteger equalsCalls = new AtomicInteger();
    public final AtomicInteger hashCodeCalls = new AtomicInteger();
    public final AtomicInteger toStringCalls = new AtomicInteger();

    @Override
    public void handle(HttpExchange exchange) {
        handleCalls.incrementAndGet();
        exchange.respond(HttpStatus.OK);
    }

    /** The calls the registry must never make on a handler while it only registers, removes or lists. */
    public int objectMethodCalls() {
        return equalsCalls.get() + hashCodeCalls.get() + toStringCalls.get();
    }

    @Override
    public boolean equals(Object other) {
        equalsCalls.incrementAndGet();
        return this == other;
    }

    @Override
    public int hashCode() {
        hashCodeCalls.incrementAndGet();
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        toStringCalls.incrementAndGet();
        return "CountingHandler";
    }
}
