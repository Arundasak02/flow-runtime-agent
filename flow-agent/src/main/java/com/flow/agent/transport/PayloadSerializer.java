package com.flow.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.agent.pipeline.RuntimeEvent;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes an event batch to JSON and then GZIP-compresses it.
 *
 * <p>Payload structure:
 * <pre>
 * {
 *   "graphId":      "order-service",
 *   "agentVersion": "0.1.0",
 *   "batch": [ { traceId, spanId, parentSpanId, nodeId, type, timestamp, durationMs, errorType }, ... ]
 * }
 * </pre>
 */
public class PayloadSerializer {

    static final String AGENT_VERSION = "0.1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serialize and GZIP-compress the batch.
     *
     * @return GZIP-compressed JSON bytes ready for HTTP POST
     * @throws Exception on serialization or compression failure
     */
    public byte[] serialize(String graphId, List<RuntimeEvent> events) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graphId", graphId);
        payload.put("agentVersion", AGENT_VERSION);
        payload.put("batch", events);

        byte[] json = objectMapper.writeValueAsBytes(payload);

        // GZIP compress
        ByteArrayOutputStream baos = new ByteArrayOutputStream(json.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(json);
        }
        return baos.toByteArray();
    }

    /**
     * Serialize without compression (used for testing / debugging).
     */
    public byte[] serializeUncompressed(String graphId, List<RuntimeEvent> events) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graphId", graphId);
        payload.put("agentVersion", AGENT_VERSION);
        payload.put("batch", events);
        return objectMapper.writeValueAsBytes(payload);
    }
}

