package eu.exeris.caps.serviceboundarycore.testsupport;

import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.api.TenantConflictException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A filter that counts every call to {@link #name()} and {@link #phase()}, whose answers can be replaced after
 * registration, and whose behaviour on a request can be chosen. Identity-based equality.
 */
public final class SpyFilter implements AdmissionFilter {

    /** What {@link #filter} does when a request reaches it. */
    public enum Mode { PASS, ANSWER_AND_HALT, HALT_WITHOUT_ANSWER, TENANT_CONFLICT, THROW }

    public final AtomicInteger nameCalls = new AtomicInteger();
    public final AtomicInteger phaseCalls = new AtomicInteger();
    public final AtomicInteger runs = new AtomicInteger();
    public volatile Supplier<String> nameSource;
    public volatile Supplier<AdmissionPhase> phaseSource;
    public volatile Mode mode = Mode.PASS;
    private final String id;
    private final List<String> trace;

    public SpyFilter(String name, AdmissionPhase phase, List<String> trace) {
        this.id = name;
        this.nameSource = () -> name;
        this.phaseSource = () -> phase;
        this.trace = trace;
    }

    public SpyFilter reportsName(String name) {
        nameSource = () -> name;
        return this;
    }

    public SpyFilter reportsPhase(AdmissionPhase phase) {
        phaseSource = () -> phase;
        return this;
    }

    public SpyFilter throwsOnName(RuntimeException failure) {
        nameSource = () -> { throw failure; };
        return this;
    }

    public SpyFilter throwsOnPhase(RuntimeException failure) {
        phaseSource = () -> { throw failure; };
        return this;
    }

    public SpyFilter mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public int calls() {
        return nameCalls.get() + phaseCalls.get();
    }

    @Override
    public String name() {
        nameCalls.incrementAndGet();
        return nameSource.get();
    }

    @Override
    public AdmissionPhase phase() {
        phaseCalls.incrementAndGet();
        return phaseSource.get();
    }

    @Override
    public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
        runs.incrementAndGet();
        trace.add(id);
        switch (mode) {
            case ANSWER_AND_HALT -> {
                exchange.respond(HttpStatus.FORBIDDEN);
                return false;
            }
            case HALT_WITHOUT_ANSWER -> {
                return false;
            }
            case TENANT_CONFLICT -> throw new TenantConflictException();
            case THROW -> throw new IllegalStateException("scripted failure");
            default -> {
                return true;
            }
        }
    }

    @Override
    public String toString() {
        return "SpyFilter[" + id + "]";
    }
}
