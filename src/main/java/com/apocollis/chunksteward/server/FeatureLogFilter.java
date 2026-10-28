package com.apocollis.chunksteward.server;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.regex.Pattern;

/**
 * A staged job that aborts inside a Forge event listener (ore generation events, for one) goes through the event bus,
 * which logs "Exception caught during firing event", the listener list and a stack trace as errors. The abort is
 * expected and handled (the chunk runs inline) and is counted in the staged populate status line, so those lines are
 * dropped here. Only lines of a terrain worker thread that follow an abort of ours are dropped; any other exception
 * keeps its log.
 */
public final class FeatureLogFilter extends AbstractFilter {
    private static final Pattern LISTENER_LINE = Pattern.compile("^\\d+: .*");
    private static final ThreadLocal<Boolean> AFTER_ABORT = new ThreadLocal<Boolean>();
    private static boolean installed;

    private FeatureLogFilter() {
    }

    /** Adds the filter to Forge's logger once. Does nothing when the logging backend is not log4j-core. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            org.apache.logging.log4j.Logger logger = LogManager.getLogger("FML");
            if (logger instanceof org.apache.logging.log4j.core.Logger) {
                ((org.apache.logging.log4j.core.Logger) logger).addFilter(new FeatureLogFilter());
            }
        } catch (Throwable ignored) {
            // The log keeps its extra lines; nothing else changes.
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event.getLevel() != Level.ERROR) {
            return Result.NEUTRAL;
        }
        String thread = event.getThreadName();
        if (thread == null || !thread.startsWith("chunksteward-terrain")) {
            return Result.NEUTRAL;
        }
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (message.startsWith("Exception caught during firing event") && causedByAbort(event.getThrown())) {
            AFTER_ABORT.set(Boolean.TRUE);
            return Result.DENY;
        }
        if (AFTER_ABORT.get() != null) {
            if (message.startsWith("Index:") || message.startsWith("Listeners:") || LISTENER_LINE.matcher(message).matches()) {
                return Result.DENY;
            }
            AFTER_ABORT.remove();
        }
        return Result.NEUTRAL;
    }

    private static boolean causedByAbort(Throwable thrown) {
        for (int depth = 0; thrown != null && depth < 8; depth++, thrown = thrown.getCause()) {
            if (thrown instanceof FeatureAbort) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, org.apache.logging.log4j.Marker marker,
                         String msg, Object... params) {
        return Result.NEUTRAL;
    }
}
