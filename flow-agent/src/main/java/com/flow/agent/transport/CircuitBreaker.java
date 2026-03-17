package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.monitor.AgentLogger;

/**
 * Three-state circuit breaker: CLOSED → OPEN (after N failures) → HALF_OPEN → CLOSED/OPEN.
 *
 * <p>Thread-safe via {@code synchronized}. Contention is minimal — only the single
 * pipeline thread calls this.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openTimestamp = 0;
    private final int failureThreshold;
    private final long resetTimeoutMs;

    public CircuitBreaker(AgentConfig.CircuitBreakerConfig config) {
        this.failureThreshold = config.getFailureThreshold();
        this.resetTimeoutMs = config.getResetTimeoutMs();
    }

    /**
     * Returns {@code true} if a request should be allowed through.
     * <ul>
     *   <li>CLOSED  — always allow</li>
     *   <li>OPEN    — deny until reset timeout expires, then transition to HALF_OPEN and allow probe</li>
     *   <li>HALF_OPEN — deny (only one probe at a time)</li>
     * </ul>
     */
    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - openTimestamp >= resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    return true; // allow one probe
                }
                return false;
            case HALF_OPEN:
                return false; // probe already in flight
            default:
                return true;
        }
    }

    /** Called when an HTTP request succeeds. Resets to CLOSED. */
    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            AgentLogger.info("Circuit breaker CLOSED — connectivity to Flow Core Service restored.");
        }
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    /** Called when an HTTP request fails. May transition to OPEN. */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openTimestamp = System.currentTimeMillis();
            AgentLogger.warn("Circuit breaker OPEN after " + consecutiveFailures
                    + " consecutive failures — events will be dropped until connectivity recovers."
                    + " Will probe again in " + resetTimeoutMs + "ms."
                    + " Check flow.server.url and network connectivity.");
        }
    }

    public synchronized State getState() { return state; }

    public synchronized int getConsecutiveFailures() { return consecutiveFailures; }
}

