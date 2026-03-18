package com.flow.agent.instrumentation;

import com.flow.agent.config.AgentConfig.CaptureConfig;
import com.flow.agent.context.FlowContext;
import com.flow.agent.instrumentation.extract.ObjectExtractor;
import com.flow.agent.monitor.AgentLogger;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.FlowEventSink;
import net.bytebuddy.asm.Advice;

import java.util.Map;

/**
 * ByteBuddy advice intercepting {@code com.flow.sdk.Flow#checkpoint()} calls.
 *
 * <p>When the developer calls {@code Flow.checkpoint("key", value)} or
 * {@code Flow.checkpoint("key", value, capture)}, the agent intercepts the call,
 * extracts the value using {@link ObjectExtractor} (respecting {@code @FlowExclude} /
 * {@code @FlowInclude} annotations and global PII patterns), and emits a
 * {@code CHECKPOINT} event to the pipeline.
 *
 * <p>The checkpoint is automatically associated with the correct graph node via
 * {@link FlowContext#currentNodeId()} — the developer does not need to specify
 * which node the checkpoint belongs to.
 *
 * <p><strong>Golden Rule:</strong> All advice code is wrapped in try-catch.
 * Extraction errors never propagate to customer code.
 */
public class CheckpointInterceptor {

    // Set during agent init — volatile for visibility across class loaders
    // Must be public because ByteBuddy inlines advice bytecode into the customer's
    // `com.flow.sdk.Flow` class. The inlined code is not "inside" this class at
    // runtime, so accessing private fields would trigger IllegalAccessError.
    public static volatile ObjectExtractor extractor;
    public static volatile boolean enabled = false;
    public static volatile boolean logOnce = false;
    // DEBUG (test/runtime diagnosis): flips to true when advice is invoked.
    // Not used for production logic.
    public static volatile boolean adviceInvoked = false;

    /**
     * Called once during agent startup to wire up the extractor.
     */
    public static void init(CaptureConfig captureConfig) {
        if (captureConfig != null && captureConfig.isEnabled()) {
            extractor = new ObjectExtractor(captureConfig);
            enabled = true;
        }
    }

    // ── Advice for Flow.checkpoint(String, Object) ──────────────────────────

    /**
     * Advice for the two-arg {@code Flow.checkpoint(String key, Object value)}.
     */
    public static class TwoArgAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onCheckpoint(
                @Advice.Argument(0) String key,
                @Advice.Argument(1) Object value) {
            try {
                adviceInvoked = true;
                if (!enabled) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " enabled=false");
                    }
                    return;
                }
                ObjectExtractor ext = extractor;
                if (ext == null) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " extractor=null");
                    }
                    return;
                }

                FlowContext ctx = FlowContext.current();
                if (ctx == null) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " ctx=null");
                    }
                    return;
                }

                String nodeId = ctx.currentNodeId();
                String lastNodeId = ctx.lastNodeId();

                // One-time proof that Flow.checkpoint(...) advice is running.
                // We log BEFORE deciding whether the nodeId is usable.
                if (!logOnce) {
                    logOnce = true;
                    AgentLogger.info(
                            "CHECKPOINT intercepted key=" + key +
                                    " nodeId=" + nodeId +
                                    " lastNodeId=" + lastNodeId
                    );
                }
                if (nodeId == null || nodeId.isBlank()) {
                    // If controller methods aren't instrumented, the span stack can be empty
                    // at the checkpoint call site. Fall back to the last nodeId we saw.
                    nodeId = lastNodeId;
                }
                if (nodeId == null || nodeId.isBlank()) return;

                Map<String, Object> data = ext.extract(key, value, null);

                FlowEventSink.emitCheckpoint(
                        ctx.getTraceId(),
                        ctx.currentSpanId(),
                        null, // parentSpanId not needed for checkpoints
                        nodeId,
                        System.currentTimeMillis(),
                        data
                );

                AgentMetrics.incrementEventsEmitted();

            } catch (Throwable t) {
                // GOLDEN RULE: never propagate to customer code
            }
        }
    }

    // ── Advice for Flow.checkpoint(String, Object, FlowCapture) ─────────────

    /**
     * Advice for the three-arg {@code Flow.checkpoint(String key, Object value, FlowCapture capture)}.
     */
    public static class ThreeArgAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onCheckpointWithCapture(
                @Advice.Argument(0) String key,
                @Advice.Argument(1) Object value,
                @Advice.Argument(2) Object capture) {
            try {
                adviceInvoked = true;
                if (!enabled) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " enabled=false");
                    }
                    return;
                }
                ObjectExtractor ext = extractor;
                if (ext == null) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " extractor=null");
                    }
                    return;
                }

                FlowContext ctx = FlowContext.current();
                if (ctx == null) {
                    if (!logOnce) {
                        logOnce = true;
                        AgentLogger.info("CHECKPOINT intercepted key=" + key + " ctx=null");
                    }
                    return;
                }

                String nodeId = ctx.currentNodeId();
                String lastNodeId = ctx.lastNodeId();

                if (!logOnce) {
                    logOnce = true;
                    AgentLogger.info(
                            "CHECKPOINT intercepted key=" + key +
                                    " nodeId=" + nodeId +
                                    " lastNodeId=" + lastNodeId
                    );
                }
                if (nodeId == null || nodeId.isBlank()) {
                    // If controller methods aren't instrumented, the span stack can be empty
                    // at the checkpoint call site. Fall back to the last nodeId we saw.
                    nodeId = lastNodeId;
                }
                if (nodeId == null || nodeId.isBlank()) return;

                Map<String, Object> data = ext.extract(key, value, capture);

                FlowEventSink.emitCheckpoint(
                        ctx.getTraceId(),
                        ctx.currentSpanId(),
                        null,
                        nodeId,
                        System.currentTimeMillis(),
                        data
                );

                AgentMetrics.incrementEventsEmitted();

            } catch (Throwable t) {
                // GOLDEN RULE: never propagate to customer code
            }
        }
    }
}

