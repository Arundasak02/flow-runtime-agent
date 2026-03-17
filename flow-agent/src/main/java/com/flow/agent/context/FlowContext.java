package com.flow.agent.context;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal-based trace context. One instance per request thread.
 *
 * <p><strong>CRITICAL:</strong> {@link #clear()} MUST be called at the end of every
 * request/entry-point exit. Failure causes trace-ID corruption on thread-pool reuse
 * (the next request on the same thread inherits the stale traceId).
 */
public class FlowContext {

    private static final ThreadLocal<FlowContext> HOLDER = new ThreadLocal<>();

    private final String traceId;
    private final String remoteParentSpanId; // spanId of the upstream caller's span (null for local root)
    private final Deque<SpanInfo> spanStack = new ArrayDeque<>();

    private FlowContext(String traceId) {
        this.traceId = traceId;
        this.remoteParentSpanId = null;
    }

    private FlowContext(String traceId, String remoteParentSpanId) {
        this.traceId = traceId;
        this.remoteParentSpanId = remoteParentSpanId;
    }

    // ── Factory / lifecycle ──────────────────────────────────────────────────

    /**
     * Returns the current context, creating a new one (with a fresh traceId) if none exists.
     * Used inside advice code when no explicit entry-point sets up a context.
     */
    public static FlowContext getOrInit() {
        FlowContext ctx = HOLDER.get();
        if (ctx == null) {
            ctx = new FlowContext(TraceIdGenerator.generate());
            HOLDER.set(ctx);
        }
        return ctx;
    }

    /**
     * Returns the current context, or {@code null} if no trace is active on this thread.
     */
    public static FlowContext current() {
        return HOLDER.get();
    }

    /**
     * Force-initialise a brand-new trace context (used by HTTP/Kafka entry-point advice).
     * Replaces any existing context on this thread.
     */
    public static void initNewTrace() {
        HOLDER.set(new FlowContext(TraceIdGenerator.generate()));
    }

    /**
     * Initialise a trace context from an upstream (distributed) caller.
     *
     * <p>Continues the upstream trace by reusing its {@code traceId}. The remote caller's
     * {@code spanId} is stored as {@code remoteParentSpanId} so the first local span links
     * into the remote call tree.
     *
     * <p>If {@code remote} is {@code null} or has no traceId, falls back to {@link #initNewTrace()}.
     *
     * @param remote the distributed context extracted from inbound headers; may be null
     * @return the initialised FlowContext
     */
    public static FlowContext initFromRemote(DistributedTraceContext remote) {
        if (remote == null || remote.getTraceId() == null || remote.getTraceId().isEmpty()) {
            initNewTrace();
            return HOLDER.get();
        }
        FlowContext ctx = new FlowContext(remote.getTraceId(), remote.getParentSpanId());
        HOLDER.set(ctx);
        return ctx;
    }

    /**
     * <strong>CRITICAL:</strong> Remove the ThreadLocal to prevent trace corruption.
     * Must be called at the end of every request / entry-point exit.
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * No-op — simply ensures the class is loaded during agent initialisation.
     */
    public static void init() { /* no-op */ }

    // ── Span stack operations ────────────────────────────────────────────────

    public String getTraceId() { return traceId; }

    /**
     * The spanId of the upstream remote caller (from distributed trace headers).
     * Used to set the {@code parentSpanId} of the root local span so it links into
     * the remote service's call tree. {@code null} if this is a brand-new trace.
     */
    public String getRemoteParentSpanId() { return remoteParentSpanId; }

    public void pushSpan(SpanInfo span) {
        spanStack.push(span);
    }

    /**
     * Pops and returns the top span, or {@code null} if the stack is empty.
     */
    public SpanInfo popSpan() {
        return spanStack.isEmpty() ? null : spanStack.pop();
    }

    /**
     * Returns the spanId of the top span without popping, or {@code null} if empty.
     * Used to determine parentSpanId for the next span pushed.
     */
    public String currentSpanId() {
        SpanInfo top = spanStack.peek();
        return top != null ? top.getSpanId() : null;
    }

    /**
     * Returns the nodeId of the top span without popping, or {@code null} if empty.
     */
    public String currentNodeId() {
        SpanInfo top = spanStack.peek();
        return top != null ? top.getNodeId() : null;
    }

    public boolean isSpanStackEmpty() {
        return spanStack.isEmpty();
    }

    public int spanDepth() {
        return spanStack.size();
    }
}

