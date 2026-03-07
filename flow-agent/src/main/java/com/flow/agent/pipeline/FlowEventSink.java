package com.flow.agent.pipeline;

import com.flow.agent.sampling.Sampler;

/**
 * Static entry point for emitting runtime events from ByteBuddy advice code.
 *
 * <p>ByteBuddy advice methods are inlined at class-load time and cannot hold instance
 * references, so this class uses a static volatile field as the bridge to the pipeline.
 *
 * <p>All methods are safe to call before {@link #init(EventRingBuffer, Sampler)} — they are
 * no-ops until the pipeline is initialised.
 */
public class FlowEventSink {

    private static volatile EventRingBuffer ringBuffer;
    private static volatile Sampler sampler;

    /** Called once during agent startup to wire up the pipeline. */
    public static void init(EventRingBuffer buffer) {
        ringBuffer = buffer;
    }

    /** Called once during agent startup to wire up the pipeline with a sampler. */
    public static void init(EventRingBuffer buffer, Sampler samplerInstance) {
        ringBuffer = buffer;
        sampler = samplerInstance;
    }

    /**
     * Emit a runtime event. Non-blocking. Never throws.
     * If the buffer is full or not yet initialised, the event is silently dropped.
     * If the sampler rejects the traceId, the event is silently dropped.
     */
    public static void emit(String traceId, String spanId, String parentSpanId,
                            String nodeId, String type, long timestamp,
                            long durationMs, String errorType) {
        EventRingBuffer buf = ringBuffer;
        if (buf == null) return; // agent not yet initialised — drop

        // Sampling gate — check before allocating RuntimeEvent
        Sampler s = sampler;
        if (s != null && !s.sample(traceId)) return;

        RuntimeEvent event = new RuntimeEvent(
                traceId, spanId, parentSpanId,
                nodeId, type, timestamp, durationMs, errorType);
        buf.offer(event);
    }
}

