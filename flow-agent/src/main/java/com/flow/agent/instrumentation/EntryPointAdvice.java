package com.flow.agent.instrumentation;

import com.flow.agent.context.DistributedTraceContext;
import com.flow.agent.context.FlowContext;
import com.flow.agent.context.TraceContextExtractor;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.FlowEventSink;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for HTTP and Kafka entry points.
 *
 * <p>Installed on Spring MVC / Spring WebFlux controllers and Kafka
 * {@code @KafkaListener} methods. Responsibilities:
 * <ol>
 *   <li><b>Distributed tracing:</b> extract an upstream traceId from inbound headers
 *       (W3C {@code traceparent}, OTel, Micrometer, Flow-native, B3) and
 *       <em>continue</em> that trace rather than starting a new one.
 *       If no upstream context exists, a fresh traceId is generated.</li>
 *   <li><b>Isolation:</b> guarantee {@link FlowContext#clear()} at request exit so no
 *       stale context leaks to the next request on the same thread-pool thread.</li>
 * </ol>
 *
 * <p>The first argument of the intercepted method is expected to be the inbound request
 * (e.g. {@code HttpServletRequest}) — ByteBuddy passes it via {@code @Advice.Argument(0)}.
 * If the argument doesn't have a {@code getHeader()} method the extractor silently returns
 * null and a fresh trace is created. This makes the advice safe for any controller signature.
 *
 * <p><strong>Thread safety:</strong> all state is ThreadLocal — no shared mutable state.
 */
public class EntryPointAdvice {

    /**
     * Extract upstream trace context from the inbound request and initialise {@link FlowContext}.
     *
     * @param request the first method argument — expected to be the inbound HTTP request or
     *                any object exposing {@code getHeader(String)}. May be null.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 0, optional = true) Object request) {
        try {
            // Try to extract upstream distributed trace context from inbound headers.
            // Handles: W3C traceparent, OTel, Micrometer, X-Flow-*, B3.
            DistributedTraceContext remote = TraceContextExtractor.extract(request);

            if (remote != null) {
                // Continue the upstream trace — same traceId, link to remote span
                FlowContext.initFromRemote(remote);
            } else {
                // No upstream context — start a fresh trace
                FlowContext.initNewTrace();
            }
            AgentMetrics.incrementEventsEmitted(); // counts as trace start
        } catch (Throwable t) {
            // GOLDEN RULE: never propagate — fall back to fresh trace
            try { FlowContext.initNewTrace(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Signals trace completion and clears the context at exit — prevents trace-context leaks on thread reuse.
     *
     * <p>Emitting a TRACE_COMPLETE event before clearing lets FCS trigger an immediate merge
     * rather than waiting for the 3-second idle timeout (Bug #5 fix).
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
        try {
            FlowContext ctx = FlowContext.current();
            if (ctx != null) {
                FlowEventSink.emitTraceComplete(ctx.getTraceId());
            }
            FlowContext.clear();
        } catch (Throwable t) {
            // GOLDEN RULE: never propagate
        }
    }
}

