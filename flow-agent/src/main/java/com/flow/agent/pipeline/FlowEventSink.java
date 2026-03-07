package com.flow.agent.pipeline;

import com.flow.agent.sampling.Sampler;

import java.util.Map;

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

    /**
     * Emit a CHECKPOINT event with extracted data.
     * Non-blocking. Never throws. If the buffer is full or not yet initialised, silently dropped.
     */
    public static void emitCheckpoint(String traceId, String spanId, String parentSpanId,
                                      String nodeId, long timestamp,
                                      Map<String, Object> data) {
        EventRingBuffer buf = ringBuffer;
        if (buf == null) return;

        Sampler s = sampler;
        if (s != null && !s.sample(traceId)) return;

        RuntimeEvent event = new RuntimeEvent(
                traceId, spanId, parentSpanId,
                nodeId, "CHECKPOINT", timestamp, 0L, null, data);
        buf.offer(event);
    }

    /**
     * Signal that a trace is complete (all spans have exited).
     *
     * <p>Emits a synthetic {@code TRACE_COMPLETE} event that the server uses to trigger
     * the merge pipeline immediately, rather than waiting for the idle-timeout scheduler.
     *
     * <p>Non-blocking. Never throws. Silently dropped if buffer is full or not initialised.
     *
     * @param traceId the completed traceId
     */
    public static void emitTraceComplete(String traceId) {
        EventRingBuffer buf = ringBuffer;
        if (buf == null) return;

        // No sampling gate here — trace-complete signals must always be sent
        // to prevent traces from being stranded in the server buffer.
        RuntimeEvent event = new RuntimeEvent(
                traceId, null, null,
                "__trace_complete__", "TRACE_COMPLETE",
                System.currentTimeMillis(), 0L, null);
        buf.offer(event);
    }
}

