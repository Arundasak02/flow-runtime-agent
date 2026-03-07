package com.flow.agent.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EventRingBuffer} — bounded non-blocking event buffer.
 */
class EventRingBufferTest {

    private EventRingBuffer buffer;

    @BeforeEach
    void setup() {
        buffer = new EventRingBuffer(4);
    }

    private RuntimeEvent makeEvent(String nodeId) {
        return new RuntimeEvent(
                "trace-1", "span-1", null, nodeId,
                "METHOD_ENTER", System.currentTimeMillis(), 0L, null
        );
    }

    @Test
    void offer_acceptsEventWhenNotFull() {
        boolean accepted = buffer.offer(makeEvent("nodeA"));
        assertTrue(accepted);
        assertEquals(1, buffer.size());
    }

    @Test
    void offer_dropsEventWhenFull() {
        // Fill buffer to capacity
        buffer.offer(makeEvent("n1"));
        buffer.offer(makeEvent("n2"));
        buffer.offer(makeEvent("n3"));
        buffer.offer(makeEvent("n4"));

        // Next offer should be dropped (non-blocking)
        boolean accepted = buffer.offer(makeEvent("n5"));
        assertFalse(accepted, "Offer must return false (drop) when buffer is full");
        assertEquals(4, buffer.size(), "Size should remain at capacity");
    }

    @Test
    void drainTo_removesEvents() {
        buffer.offer(makeEvent("n1"));
        buffer.offer(makeEvent("n2"));
        buffer.offer(makeEvent("n3"));

        List<RuntimeEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 10);

        assertEquals(3, count);
        assertEquals(3, drained.size());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void drainTo_respectsMaxCount() {
        buffer.offer(makeEvent("n1"));
        buffer.offer(makeEvent("n2"));
        buffer.offer(makeEvent("n3"));

        List<RuntimeEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 2);

        assertEquals(2, count);
        assertEquals(1, buffer.size(), "One event should remain");
    }

    @Test
    void drainTo_emptyBuffer_returnsZero() {
        List<RuntimeEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 100);
        assertEquals(0, count);
        assertTrue(drained.isEmpty());
    }

    @Test
    void isEmpty_trueWhenEmpty() {
        assertTrue(buffer.isEmpty());
    }

    @Test
    void isEmpty_falseAfterOffer() {
        buffer.offer(makeEvent("n1"));
        assertFalse(buffer.isEmpty());
    }

    @Test
    void offerDoesNotBlock_applicationThread() throws InterruptedException {
        // Fill to capacity
        for (int i = 0; i < 4; i++) {
            buffer.offer(makeEvent("n" + i));
        }

        long before = System.nanoTime();
        // This must NOT block
        buffer.offer(makeEvent("overflow"));
        long elapsed = System.nanoTime() - before;

        // Should complete in well under 1ms (we allow 50ms for CI variance)
        assertTrue(elapsed < 50_000_000L,
                "offer() must be non-blocking; took " + elapsed + " ns");
    }
}

