package com.flow.agent.pipeline;

import com.flow.agent.monitor.AgentMetrics;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded, non-blocking event buffer backed by an {@link ArrayBlockingQueue}.
 *
 * <p>{@link #offer(RuntimeEvent)} is always non-blocking. If the buffer is full,
 * the event is silently dropped and the drop counter is incremented.
 * This is intentional — the Golden Rule requires we never block the application thread.
 */
public class EventRingBuffer {

    private final ArrayBlockingQueue<RuntimeEvent> queue;

    public EventRingBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking enqueue. Returns {@code true} if accepted, {@code false} if dropped.
     * Dropped events increment {@link AgentMetrics#incrementEventsDropped()}.
     */
    public boolean offer(RuntimeEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            AgentMetrics.incrementEventsDropped();
        }
        return accepted;
    }

    /**
     * Drains up to {@code maxCount} events into {@code target}.
     *
     * @return number of events drained
     */
    public int drainTo(List<RuntimeEvent> target, int maxCount) {
        return queue.drainTo(target, maxCount);
    }

    public int size() { return queue.size(); }

    public boolean isEmpty() { return queue.isEmpty(); }

    public int capacity() { return queue.size() + queue.remainingCapacity(); }
}

