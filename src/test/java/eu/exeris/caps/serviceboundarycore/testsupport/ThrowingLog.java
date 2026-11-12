package eu.exeris.caps.serviceboundarycore.testsupport;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs a handler on the java.util.logging backend of a {@link System.Logger} that runs a hook for every
 * record it is given, and restores the logger's level, handlers and parent chaining on close. The hook may
 * throw: the exception leaves {@code publish} exactly as a failing handler's does.
 */
public final class ThrowingLog implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final boolean previousUseParent;
    private final AtomicInteger published = new AtomicInteger();
    private final Handler handler;

    public ThrowingLog(Class<?> source, Consumer<LogRecord> hook) {
        this.logger = Logger.getLogger(source.getName());
        this.previousLevel = logger.getLevel();
        this.previousUseParent = logger.getUseParentHandlers();
        this.handler = new Handler() {
            @Override public void publish(LogRecord record) {
                published.incrementAndGet();
                hook.accept(record);
            }

            @Override public void flush() { }

            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    /** A handler that throws the given unchecked throwable for every record. */
    public static ThrowingLog throwing(Class<?> source, Throwable failure) {
        return new ThrowingLog(source, record -> sneaky(failure));
    }

    /** How many records reached the handler. */
    public int published() {
        return published.get();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousUseParent);
    }
}
