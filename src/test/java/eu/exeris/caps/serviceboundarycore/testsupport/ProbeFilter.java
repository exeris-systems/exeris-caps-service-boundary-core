package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A filter that counts every call the registry could make on it ({@code name}, {@code phase}, {@code filter},
 * {@code equals}, {@code hashCode}, {@code toString}), whose {@code equals} is scripted and whose {@code name}
 * and {@code phase} can run a hook. By default it is equal to itself only.
 */
public final class ProbeFilter implements AdmissionFilter {

    public final AtomicInteger nameCalls = new AtomicInteger();
    public final AtomicInteger phaseCalls = new AtomicInteger();
    public final AtomicInteger filterCalls = new AtomicInteger();
    public final AtomicInteger equalsCalls = new AtomicInteger();
    public final AtomicInteger hashCodeCalls = new AtomicInteger();
    public final AtomicInteger toStringCalls = new AtomicInteger();
    public volatile Predicate<Object> equalsRule;
    public volatile Runnable onName = () -> { };
    public volatile Runnable onPhase = () -> { };
    private final String name;
    private final AdmissionPhase phase;
    private final List<String> trace;

    public ProbeFilter(String name, AdmissionPhase phase, List<String> trace) {
        this.name = name;
        this.phase = phase;
        this.trace = trace;
        this.equalsRule = other -> other == this;
    }

    public ProbeFilter equalToEvery(Class<?> type) {
        equalsRule = type::isInstance;
        return this;
    }

    public ProbeFilter equalsRule(Predicate<Object> rule) {
        equalsRule = rule;
        return this;
    }

    public ProbeFilter onName(Runnable hook) {
        onName = hook;
        return this;
    }

    public ProbeFilter onPhase(Runnable hook) {
        onPhase = hook;
        return this;
    }

    /** The calls the registry must never make on a filter. */
    public int objectMethodCalls() {
        return equalsCalls.get() + hashCodeCalls.get() + toStringCalls.get();
    }

    /** The name this filter was built with, read without counting. */
    public String label() {
        return name;
    }

    @Override
    public String name() {
        nameCalls.incrementAndGet();
        onName.run();
        return name;
    }

    @Override
    public AdmissionPhase phase() {
        phaseCalls.incrementAndGet();
        onPhase.run();
        return phase;
    }

    @Override
    public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
        filterCalls.incrementAndGet();
        trace.add(name);
        return true;
    }

    @Override
    public boolean equals(Object other) {
        equalsCalls.incrementAndGet();
        return equalsRule.test(other);
    }

    @Override
    public int hashCode() {
        hashCodeCalls.incrementAndGet();
        return 1;
    }

    @Override
    public String toString() {
        toStringCalls.incrementAndGet();
        return "ProbeFilter[" + name + "]";
    }
}
