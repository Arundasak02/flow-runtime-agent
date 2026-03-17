package com.flow.agent.context;

/**
 * Carries distributed trace context extracted from an inbound HTTP/Kafka request.
 *
 * <p>Immutable value object passed from {@link TraceContextExtractor} to
 * {@link FlowContext#initFromRemote(DistributedTraceContext)}.
 *
 * <p>Header priority (highest → lowest):
 * <ol>
 *   <li>W3C {@code traceparent} — {@code 00-<traceId>-<parentSpanId>-<flags>}</li>
 *   <li>OpenTelemetry (reflective bridge via {@code io.opentelemetry.api.trace.Span})</li>
 *   <li>Micrometer Tracing (reflective bridge via {@code io.micrometer.tracing.Tracer})</li>
 *   <li>{@code X-Flow-Trace-Id} + {@code X-Flow-Span-Id} (Flow-native headers)</li>
 *   <li>B3 single-header — {@code b3: <traceId>-<spanId>[-<flags>[-<parentSpanId>]]}</li>
 *   <li>B3 multi-header — {@code X-B3-TraceId} + {@code X-B3-SpanId}</li>
 * </ol>
 */
public class DistributedTraceContext {

    private final String traceId;
    private final String parentSpanId;   // the remote service's span — becomes our root span's parent
    private final String source;         // e.g. "w3c-traceparent", "otel", "flow-native"

    public DistributedTraceContext(String traceId, String parentSpanId, String source) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.source = source;
    }

    /** The trace ID to continue (from upstream service). */
    public String getTraceId() { return traceId; }

    /**
     * The span ID of the remote caller's span.
     * Our root span sets this as its {@code parentSpanId} to link into the distributed tree.
     */
    public String getParentSpanId() { return parentSpanId; }

    /** Identifies which header/protocol provided the context (for debugging). */
    public String getSource() { return source; }

    @Override
    public String toString() {
        return "DistributedTraceContext{traceId='" + traceId + '\''
                + ", parentSpanId='" + parentSpanId + '\''
                + ", source='" + source + "'}";
    }
}

