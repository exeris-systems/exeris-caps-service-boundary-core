package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A filter whose {@code equals} is scripted: it counts its calls, can run a hook, and answers by a rule. By
 * default it is equal to itself only.
 */
public final class ProbeFilter implements AdmissionFilter {

    public final AtomicInteger equalsCalls = new AtomicInteger();
    public volatile Predicate<Object> equalsRule;
    public volatile Runnable onEquals = () -> { };
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

    public ProbeFilter onEquals(Runnable hook) {
        onEquals = hook;
        return this;
    }

    @Override public String name() { return name; }

    @Override public AdmissionPhase phase() { return phase; }

    @Override
    public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
        trace.add(name);
        return true;
    }

    @Override
    public boolean equals(Object other) {
        equalsCalls.incrementAndGet();
        onEquals.run();
        return equalsRule.test(other);
    }

    @Override
    public int hashCode() {
        return 1;
    }

    @Override
    public String toString() {
        return "ProbeFilter[" + name + "]";
    }
}
