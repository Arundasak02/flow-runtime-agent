package com.flow.agent.pipeline;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.monitor.AgentLogger;
import com.flow.agent.transport.HttpBatchSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Daemon thread that continuously drains the ring buffer and flushes batches to FCS.
 *
 * <p>Flush triggers (whichever comes first):
 * <ul>
 *   <li>Batch size reaches {@code batchSize}</li>
 *   <li>Time since last flush exceeds {@code flushIntervalMs} and buffer is non-empty</li>
 * </ul>
 *
 * <p>This thread is marked daemon so it does not keep the JVM alive after the customer app exits.
 */
public class BatchAssembler {

    private final EventRingBuffer ringBuffer;
    private final HttpBatchSender sender;
    private final int batchSize;
    private final int flushIntervalMs;
    private final String graphId;
    private volatile boolean running = true;

    public BatchAssembler(EventRingBuffer ringBuffer, HttpBatchSender sender,
                          AgentConfig.PipelineConfig pipelineConfig, String graphId) {
        this.ringBuffer = ringBuffer;
        this.sender = sender;
        this.batchSize = pipelineConfig.getBatchSize();
        this.flushIntervalMs = pipelineConfig.getFlushIntervalMs();
        this.graphId = graphId;
    }

    /** Start the daemon thread. */
    public void start() {
        Thread thread = new Thread(this::run, "flow-agent-pipeline");
        thread.setDaemon(true);  // never prevents JVM shutdown
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void run() {
        List<RuntimeEvent> batch = new ArrayList<>(batchSize);
        long lastFlushTime = System.currentTimeMillis();

        while (running) {
            try {
                // Drain up to batchSize events from the buffer
                ringBuffer.drainTo(batch, batchSize);

                long now = System.currentTimeMillis();
                boolean countTrigger = batch.size() >= batchSize;
                boolean timeTrigger = (now - lastFlushTime) >= flushIntervalMs && !batch.isEmpty();

                if (countTrigger || timeTrigger) {
                    sender.send(graphId, new ArrayList<>(batch));
                    batch.clear();
                    lastFlushTime = now;
                }

                // Avoid busy-spinning when the buffer is empty
                if (batch.isEmpty()) {
                    Thread.sleep(10);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Never crash the pipeline thread — log and continue
                AgentLogger.warn("Pipeline error — batch discarded, resuming", t);
                batch.clear();
            }
        }

        // Final flush on shutdown
        if (!batch.isEmpty()) {
            try {
                sender.send(graphId, batch);
            } catch (Throwable ignored) { /* best-effort */ }
        }
    }

    public void stop() {
        running = false;
    }
}

