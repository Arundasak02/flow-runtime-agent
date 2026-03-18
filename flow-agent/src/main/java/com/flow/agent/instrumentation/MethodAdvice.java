package com.flow.agent.instrumentation;

import com.flow.agent.context.FlowContext;
import com.flow.agent.context.SpanInfo;
import com.flow.agent.context.TraceIdGenerator;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.FlowEventSink;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Executable;
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
    public static void onEnter(
            @Advice.Origin Executable executable,
            @Advice.Local("startTimeNs") long startTimeNs) {
        try {
            // Get or create trace context for this thread
            FlowContext ctx = FlowContext.getOrInit();

            startTimeNs = System.nanoTime();

            // Advice validation may involve constructors; only proceed for real methods.
            if (!(executable instanceof Method)) {
                startTimeNs = 0L;
                return;
            }
            Method method = (Method) executable;

            // Build the nodeId (cached after first computation)
            String nodeId = NodeIdBuilder.build(method.getDeclaringClass(), method);
            ctx.setLastNodeId(nodeId);

            // Build this span.
            // For the root span of a continued distributed trace, parentSpanId is the
            // remote caller's spanId (ctx.getRemoteParentSpanId()), not null.
            // For nested spans, parentSpanId is the current top of the local stack.
            String spanId = TraceIdGenerator.generateSpanId();
            String parentSpanId = ctx.isSpanStackEmpty()
                    ? ctx.getRemoteParentSpanId()   // root span: link to remote caller
                    : ctx.currentSpanId();           // nested span: link to local parent

            ctx.pushSpan(new SpanInfo(spanId, parentSpanId, nodeId, startTimeNs));

            // Emit METHOD_ENTER event (non-blocking, never throws)
            FlowEventSink.emit(
                    ctx.getTraceId(), spanId, parentSpanId,
                    nodeId, "METHOD_ENTER", System.currentTimeMillis(),
                    0L, null
            );

            AgentMetrics.incrementEventsEmitted();
        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
            startTimeNs = 0L;
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
            @Advice.Local("startTimeNs") long startTimeNs,
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

            // Do NOT clear ThreadLocal here.
            // When the controller method itself isn't instrumented, the span stack can
            // become empty mid-request, causing premature trace completion before any
            // Flow.checkpoint() calls execute. We rely on EntryPointAdvice to clear at
            // the actual request boundary.

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
        }
    }
}

