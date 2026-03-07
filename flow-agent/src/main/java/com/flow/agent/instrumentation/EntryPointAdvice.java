package com.flow.agent.instrumentation;

import com.flow.agent.context.FlowContext;
import com.flow.agent.monitor.AgentMetrics;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for HTTP and Kafka entry points.
 *
 * <p>Installed on Spring MVC / Spring WebFlux controllers and Kafka
 * {@code @KafkaListener} methods. Its job is to:
 * <ol>
 *   <li>Force-initialise a <em>new</em> {@link FlowContext} (fresh traceId) at request entry.</li>
 *   <li>Guarantee {@link FlowContext#clear()} at request exit so no stale context leaks to the
 *       next request on the same thread-pool thread.</li>
 * </ol>
 *
 * <p>Without this, the first instrumented method inside the request triggers
 * {@link FlowContext#getOrInit()} which may reuse a stale context left from a previous request
 * (if MethodAdvice's root-span cleanup was somehow missed).
 *
 * <p><strong>Phase 1 note:</strong> This advice is currently installed but not wired to a specific
 * entry-point matcher in {@link FlowTransformer}. Full HTTP/Kafka entry detection is wired in
 * Phase 2 when OTel bridge is added. The class is here for completeness and to make the
 * clear-on-exit contract explicit.
 */
public class EntryPointAdvice {

    /**
     * Force a new trace context at entry — ensures traceId isolation per request.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
        try {
            FlowContext.initNewTrace();
            AgentMetrics.incrementEventsEmitted(); // counts as trace start
        } catch (Throwable t) {
            // GOLDEN RULE: never propagate
        }
    }

    /**
     * Always clear the context at exit — prevents trace-context leaks on thread reuse.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
        try {
            FlowContext.clear();
        } catch (Throwable t) {
            // GOLDEN RULE: never propagate
        }
    }
}

