package com.flow.agent.context;

/**
 * Represents a single method execution in the span stack.
 * Immutable value object.
 */
public class SpanInfo {

    private final String spanId;
    private final String parentSpanId;   // null for root span
    private final String nodeId;
    private final long startTimeNs;

    public SpanInfo(String spanId, String parentSpanId, String nodeId, long startTimeNs) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.nodeId = nodeId;
        this.startTimeNs = startTimeNs;
    }

    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getNodeId() { return nodeId; }
    public long getStartTimeNs() { return startTimeNs; }

    @Override
    public String toString() {
        return "SpanInfo{spanId='" + spanId + '\''
             + ", parentSpanId='" + parentSpanId + '\''
             + ", nodeId='" + nodeId + '\''
             + ", startTimeNs=" + startTimeNs + '}';
    }
}

