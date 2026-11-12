package eu.exeris.caps.serviceboundarycore.testsupport;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what a {@link System.Logger} of the given name publishes, through the java.util.logging backend,
 * and restores the logger's level, handlers and parent chaining on close.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final boolean previousUseParent;
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) {
            synchronized (records) {
                records.add(record);
            }
        }

        @Override public void flush() { }

        @Override public void close() { }
    };

    public LogCapture(Class<?> source) {
        this.logger = Logger.getLogger(source.getName());
        this.previousLevel = logger.getLevel();
        this.previousUseParent = logger.getUseParentHandlers();
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    public List<LogRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public List<LogRecord> atOrAbove(Level level) {
        return records().stream().filter(r -> r.getLevel().intValue() >= level.intValue()).toList();
    }

    public List<LogRecord> at(Level level) {
        return records().stream().filter(r -> r.getLevel().equals(level)).toList();
    }

    /** Everything a record carries as text: formatted message, raw message, parameters, and the throwable chain. */
    public static String everything(LogRecord record) {
        StringBuilder text = new StringBuilder();
        String message = record.getMessage();
        text.append(message).append('\n');
        Object[] parameters = record.getParameters();
        if (parameters != null && message != null) {
            for (Object parameter : parameters) {
                text.append(parameter).append('\n');
            }
            try {
                text.append(MessageFormat.format(message, parameters)).append('\n');
            } catch (IllegalArgumentException ignored) {
                // an unformattable pattern still contributes its raw text above
            }
        }
        return text.append(describeThrown(record.getThrown())).toString();
    }

    /** The formatted message and parameters only, without the throwable. */
    public static String messageText(LogRecord record) {
        String full = everything(new LogRecordWithoutThrown(record));
        return full;
    }

    private static String describeThrown(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            text.append(t).append('\n');
            for (Throwable suppressed : t.getSuppressed()) {
                text.append(describeThrown(suppressed));
            }
        }
        return text.toString();
    }

    private static final class LogRecordWithoutThrown extends LogRecord {
        LogRecordWithoutThrown(LogRecord source) {
            super(source.getLevel(), source.getMessage());
            setParameters(source.getParameters());
        }
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousUseParent);
    }
}
