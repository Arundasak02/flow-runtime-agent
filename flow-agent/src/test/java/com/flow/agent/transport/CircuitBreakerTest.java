package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CircuitBreaker} — CLOSED/OPEN/HALF_OPEN state machine.
 */
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setup() {
        AgentConfig.CircuitBreakerConfig config = new AgentConfig.CircuitBreakerConfig();
        config.setFailureThreshold(3);
        config.setResetTimeoutMs(100); // short timeout for tests
        circuitBreaker = new CircuitBreaker(config);
    }

    @Test
    void initialState_isClosed_allowsRequests() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void failuresBeforeThreshold_staysClosed() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void failuresAtThreshold_opensCircuit() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void openCircuit_blocksRequests() {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure();
        assertFalse(circuitBreaker.allowRequest());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void openCircuit_transitionsToHalfOpen_afterTimeout() throws InterruptedException {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for reset timeout
        Thread.sleep(150);

        // First allowRequest after timeout should return true and transition to HALF_OPEN
        assertTrue(circuitBreaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void halfOpen_successClosesCircuit() throws InterruptedException {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.allowRequest(); // probe — transitions to HALF_OPEN

        circuitBreaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void halfOpen_failureReopensCircuit() throws InterruptedException {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.allowRequest(); // probe

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    void successResetsFailureCounter() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordSuccess(); // reset

        // Should need 3 more failures to open
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
}

