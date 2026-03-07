package com.flow.agent.pipeline;

/**
 * A single runtime event to be shipped to Flow Core Service.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code METHOD_ENTER} — method execution started (durationMs = 0)</li>
 *   <li>{@code METHOD_EXIT}  — method completed normally (durationMs = actual)</li>
 *   <li>{@code ERROR}        — method threw an exception (durationMs = actual, errorType set)</li>
 * </ul>
 */
public class RuntimeEvent {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;  // null for root span
    private final String nodeId;
    private final String type;          // METHOD_ENTER | METHOD_EXIT | ERROR
    private final long timestamp;       // epoch milliseconds
    private final long durationMs;      // 0 for ENTER events
    private final String errorType;     // null unless type == ERROR

    public RuntimeEvent(String traceId, String spanId, String parentSpanId,
                        String nodeId, String type, long timestamp,
                        long durationMs, String errorType) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.nodeId = nodeId;
        this.type = type;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.errorType = errorType;
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }
    public String getErrorType() { return errorType; }

    @Override
    public String toString() {
        return "RuntimeEvent{type='" + type + '\''
             + ", nodeId='" + nodeId + '\''
             + ", traceId='" + traceId + '\''
             + ", spanId='" + spanId + '\''
             + ", durationMs=" + durationMs + '}';
    }
}

