package com.flow.agent.monitor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralised logger for the Flow Runtime Agent.
 *
 * <h3>Design goals</h3>
 * <ul>
 *   <li><b>Zero dependency</b> — uses {@link java.util.logging} (JUL), which is always
 *       present in the JVM. Logback, Log4j2, and Log4j all ship JUL bridges, so log
 *       output will appear in whatever framework the client application uses.</li>
 *   <li><b>Level-guarded</b> — DEBUG/TRACE calls are free on the hot path when the
 *       level is not enabled.</li>
 *   <li><b>Never throws</b> — every method swallows its own exceptions to honour the
 *       agent golden rule.</li>
 *   <li><b>Structured prefix</b> — every line is prefixed with {@code [flow-agent]} so
 *       log aggregators (Datadog, Splunk, ELK) can filter agent logs instantly.</li>
 * </ul>
 *
 * <h3>Log levels used</h3>
 * <pre>
 *   INFO   — agent lifecycle (start, stop, circuit-breaker state changes)
 *   WARN   — recoverable problems (failed batch, dropped events, transform errors)
 *   ERROR  — non-recoverable problems that degrade functionality (init failure)
 *   DEBUG  — per-request detail (disabled in production by default)
 *   FINE   — ByteBuddy transform events (very verbose — off unless actively debugging)
 * </pre>
 *
 * <h3>Enabling debug output</h3>
 * Set the JVM system property at startup:
 * <pre>
 *   -Dflow.agent.log.level=FINE
 * </pre>
 * Or in {@code logging.properties}:
 * <pre>
 *   com.flow.agent.level = FINE
 * </pre>
 */
public final class AgentLogger {

    private static final String LOGGER_NAME = "com.flow.agent";
    private static final String PREFIX = "[flow-agent] ";
    private static final Logger LOGGER;

    static {
        LOGGER = Logger.getLogger(LOGGER_NAME);
        // Allow override via system property at startup: -Dflow.agent.log.level=FINE
        String levelProp = System.getProperty("flow.agent.log.level");
        if (levelProp != null) {
            try {
                LOGGER.setLevel(Level.parse(levelProp.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // invalid level string — leave default
            }
        }
    }

    private AgentLogger() {}

    // ── INFO ──────────────────────────────────────────────────────────────────

    /** Lifecycle and state-change events (always visible in production). */
    public static void info(String message) {
        try {
            LOGGER.info(PREFIX + message);
        } catch (Throwable ignored) {}
    }

    // ── WARN ──────────────────────────────────────────────────────────────────

    /** Recoverable problem — functionality is degraded but the app is unaffected. */
    public static void warn(String message) {
        try {
            LOGGER.warning(PREFIX + message);
        } catch (Throwable ignored) {}
    }

    /** Recoverable problem with a root cause. */
    public static void warn(String message, Throwable cause) {
        try {
            LOGGER.log(Level.WARNING, PREFIX + message, cause);
        } catch (Throwable ignored) {}
    }

    // ── ERROR ─────────────────────────────────────────────────────────────────

    /** Non-recoverable problem — agent functionality is partially or fully disabled. */
    public static void error(String message) {
        try {
            LOGGER.severe(PREFIX + message);
        } catch (Throwable ignored) {}
    }

    /** Non-recoverable problem with root cause. */
    public static void error(String message, Throwable cause) {
        try {
            LOGGER.log(Level.SEVERE, PREFIX + message, cause);
        } catch (Throwable ignored) {}
    }

    // ── DEBUG ─────────────────────────────────────────────────────────────────

    /**
     * Per-request / per-batch detail. Disabled by default in production.
     * Only emitted when level is FINE or lower.
     */
    public static void debug(String message) {
        try {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(PREFIX + message);
            }
        } catch (Throwable ignored) {}
    }

    /** Debug with lazy string supplier — zero allocation when disabled. */
    public static void debug(java.util.function.Supplier<String> messageSupplier) {
        try {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(PREFIX + messageSupplier.get());
            }
        } catch (Throwable ignored) {}
    }

    // ── TRACE ─────────────────────────────────────────────────────────────────

    /**
     * Very verbose — ByteBuddy transform events, per-event detail.
     * Only emitted when level is FINEST or lower.
     */
    public static void trace(String message) {
        try {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest(PREFIX + message);
            }
        } catch (Throwable ignored) {}
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /** Returns true if DEBUG (FINE) logging is enabled — use to avoid building expensive strings. */
    public static boolean isDebugEnabled() {
        return LOGGER.isLoggable(Level.FINE);
    }
}

