package com.flow.agent.instrumentation;

import com.flow.agent.context.FlowContext;
import com.flow.agent.context.SpanInfo;
import com.flow.agent.context.TraceIdGenerator;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.FlowEventSink;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice inlined into every instrumented method.
 *
 * <p>Runs on the <strong>application thread</strong> — must be nanosecond-fast and must never
 * throw. Every block is wrapped in try-catch to guarantee the Golden Rule: advice exceptions
 * never propagate to customer code.
 *
 * <p><strong>CRITICAL:</strong> {@link FlowContext#clear()} is called when the root span exits
 * (span stack becomes empty). Without this, the next request on the same thread-pool thread
 * inherits the stale traceId, causing silent trace corruption.
 */
public class MethodAdvice {

    /**
     * Runs at method entry. Returns the start timestamp (nanoseconds) to pass to onExit.
     * Returns {@code 0L} if anything goes wrong — onExit will skip processing.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter(
            @Advice.Origin Method method,
            @Advice.Origin Class<?> declaringClass) {
        try {
            // Get or create trace context for this thread
            FlowContext ctx = FlowContext.getOrInit();

            long startTimeNs = System.nanoTime();

            // Build the nodeId (cached after first computation)
            String nodeId = NodeIdBuilder.build(declaringClass, method);

            // Build this span
            String spanId = TraceIdGenerator.generateSpanId();
            String parentSpanId = ctx.currentSpanId(); // null if this is the root span

            ctx.pushSpan(new SpanInfo(spanId, parentSpanId, nodeId, startTimeNs));

            // Emit METHOD_ENTER event (non-blocking, never throws)
            FlowEventSink.emit(
                    ctx.getTraceId(), spanId, parentSpanId,
                    nodeId, "METHOD_ENTER", System.currentTimeMillis(),
                    0L, null
            );

            AgentMetrics.incrementEventsEmitted();
            return startTimeNs;

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
            return 0L;
        }
    }

    /**
     * Runs at method exit (normal or exception).
     *
     * @param startTimeNs the value returned by {@link #onEnter} — 0 means enter failed
     * @param thrown      non-null if the method threw an exception
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter long startTimeNs,
            @Advice.Origin Method method,
            @Advice.Origin Class<?> declaringClass,
            @Advice.Thrown Throwable thrown) {
        try {
            if (startTimeNs == 0L) return; // enter failed — nothing to clean up

            FlowContext ctx = FlowContext.current();
            if (ctx == null) return;

            long durationMs = (System.nanoTime() - startTimeNs) / 1_000_000;

            SpanInfo span = ctx.popSpan();
            if (span == null) return;

            // ERROR event if exception was thrown, METHOD_EXIT otherwise
            String type = (thrown != null) ? "ERROR" : "METHOD_EXIT";
            String errorType = (thrown != null) ? thrown.getClass().getName() : null;

            FlowEventSink.emit(
                    ctx.getTraceId(), span.getSpanId(), span.getParentSpanId(),
                    span.getNodeId(), type, System.currentTimeMillis(),
                    durationMs, errorType
            );

            AgentMetrics.incrementEventsEmitted();

            // CRITICAL: clear ThreadLocal when root span exits to prevent trace corruption
            if (ctx.isSpanStackEmpty()) {
                // Signal trace completion so the server triggers the merge pipeline
                // immediately rather than waiting for the idle-timeout scheduler.
                FlowEventSink.emitTraceComplete(ctx.getTraceId());
                FlowContext.clear();
            }

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
        }
    }
}

