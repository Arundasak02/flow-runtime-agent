package com.flow.agent.pipeline;

import java.util.Map;

/**
 * A single runtime event to be shipped to Flow Core Service.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code METHOD_ENTER}  — method execution started (durationMs = 0)</li>
 *   <li>{@code METHOD_EXIT}   — method completed normally (durationMs = actual)</li>
 *   <li>{@code ERROR}         — method threw an exception (durationMs = actual, errorType set)</li>
 *   <li>{@code CHECKPOINT}    — developer-placed checkpoint (data contains key-value pairs)</li>
 * </ul>
 */
public class RuntimeEvent {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;  // null for root span
    private final String nodeId;
    private final String type;          // METHOD_ENTER | METHOD_EXIT | ERROR | CHECKPOINT
    private final long timestamp;       // epoch milliseconds
    private final long durationMs;      // 0 for ENTER/CHECKPOINT events
    private final String errorType;     // null unless type == ERROR
    private final Map<String, Object> data;  // null unless type == CHECKPOINT

    public RuntimeEvent(String traceId, String spanId, String parentSpanId,
                        String nodeId, String type, long timestamp,
                        long durationMs, String errorType) {
        this(traceId, spanId, parentSpanId, nodeId, type, timestamp, durationMs, errorType, null);
    }

    public RuntimeEvent(String traceId, String spanId, String parentSpanId,
                        String nodeId, String type, long timestamp,
                        long durationMs, String errorType,
                        Map<String, Object> data) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.nodeId = nodeId;
        this.type = type;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.errorType = errorType;
        this.data = data;
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }
    public String getErrorType() { return errorType; }

    /** Returns checkpoint data map, or {@code null} for non-CHECKPOINT events. */
    public Map<String, Object> getData() { return data; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RuntimeEvent{type='").append(type).append('\'')
                .append(", nodeId='").append(nodeId).append('\'')
                .append(", traceId='").append(traceId).append('\'')
                .append(", spanId='").append(spanId).append('\'')
                .append(", durationMs=").append(durationMs);
        if (data != null && !data.isEmpty()) {
            sb.append(", data=").append(data);
        }
        sb.append('}');
        return sb.toString();
    }
}

