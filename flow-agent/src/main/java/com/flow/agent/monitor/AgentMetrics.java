package com.flow.agent.monitor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent self-monitoring counters.
 *
 * <p>All counters are {@link AtomicLong} for lock-free updates on the hot path.
 * A daemon thread logs and resets the counters on a configurable interval.
 */
public class AgentMetrics {

    private static final AtomicLong eventsEmitted  = new AtomicLong();
    private static final AtomicLong eventsDropped  = new AtomicLong();
    private static final AtomicLong batchesSent    = new AtomicLong();
    private static final AtomicLong batchesFailed  = new AtomicLong();

    // ── Counter increments (called from hot paths) ───────────────────────────

    public static void incrementEventsEmitted()           { eventsEmitted.incrementAndGet(); }
    public static void incrementEventsDropped()           { eventsDropped.incrementAndGet(); }
    public static void incrementEventsDropped(int count)  { eventsDropped.addAndGet(count); }
    public static void incrementBatchesSent()             { batchesSent.incrementAndGet(); }
    public static void incrementBatchesFailed()           { batchesFailed.incrementAndGet(); }

    // ── Snapshot reads (for tests) ───────────────────────────────────────────

    public static long getEventsEmitted()  { return eventsEmitted.get(); }
    public static long getEventsDropped()  { return eventsDropped.get(); }
    public static long getBatchesSent()    { return batchesSent.get(); }
    public static long getBatchesFailed()  { return batchesFailed.get(); }

    // ── Periodic logging ─────────────────────────────────────────────────────

    /**
     * Start a daemon thread that logs and resets all counters every {@code intervalSeconds}.
     */
    public static void startPeriodicLog(int intervalSeconds) {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                    logAndReset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "flow-agent-metrics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    static void logAndReset() {
        long emitted = eventsEmitted.getAndSet(0);
        long dropped = eventsDropped.getAndSet(0);
        long sent    = batchesSent.getAndSet(0);
        long failed  = batchesFailed.getAndSet(0);
        System.out.println("[flow-agent] events=" + emitted + "/period"
                + " dropped=" + dropped
                + " batches_sent=" + sent
                + " batches_failed=" + failed);
    }
}

