package com.flow.agent.transport;

import com.flow.agent.pipeline.RuntimeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PayloadSerializer} — JSON serialization + GZIP compression.
 */
class PayloadSerializerTest {

    private PayloadSerializer serializer;

    @BeforeEach
    void setup() {
        serializer = new PayloadSerializer();
    }

    private RuntimeEvent makeEvent() {
        return new RuntimeEvent(
                "trace-123", "span-abc", null,
                "com.example.Service#doWork(String):void",
                "METHOD_ENTER", 1735412200100L, 0L, null
        );
    }

    @Test
    void serialize_producesGzipOutput() throws Exception {
        byte[] bytes = serializer.serialize("order-service", List.of(makeEvent()));
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        // Verify it's valid GZIP — decompress should not throw
        String json = decompress(bytes);
        assertNotNull(json);
        assertFalse(json.isEmpty());
    }

    @Test
    void serialize_jsonContainsGraphId() throws Exception {
        byte[] bytes = serializer.serialize("order-service", List.of(makeEvent()));
        String json = decompress(bytes);
        assertTrue(json.contains("\"graphId\":\"order-service\""));
    }

    @Test
    void serialize_jsonContainsAgentVersion() throws Exception {
        byte[] bytes = serializer.serialize("order-service", List.of(makeEvent()));
        String json = decompress(bytes);
        assertTrue(json.contains("\"agentVersion\""), "agentVersion must be in payload");
    }

    @Test
    void serialize_jsonContainsBatchArray() throws Exception {
        byte[] bytes = serializer.serialize("order-service", List.of(makeEvent()));
        String json = decompress(bytes);
        assertTrue(json.contains("\"batch\""), "batch field must be present");
    }

    @Test
    void serialize_jsonContainsEventFields() throws Exception {
        byte[] bytes = serializer.serialize("order-service", List.of(makeEvent()));
        String json = decompress(bytes);
        assertTrue(json.contains("trace-123"), "traceId must be in payload");
        assertTrue(json.contains("span-abc"), "spanId must be in payload");
        assertTrue(json.contains("METHOD_ENTER"), "type must be in payload");
        assertTrue(json.contains("com.example.Service#doWork"), "nodeId must be in payload");
    }

    @Test
    void serialize_multipleBatchEvents() throws Exception {
        List<RuntimeEvent> events = List.of(
                new RuntimeEvent("t1", "s1", null, "NodeA#m():void", "METHOD_ENTER", 1000L, 0L, null),
                new RuntimeEvent("t1", "s2", "s1", "NodeB#m():void", "METHOD_EXIT", 1001L, 5L, null)
        );
        byte[] bytes = serializer.serialize("svc", events);
        String json = decompress(bytes);
        assertTrue(json.contains("NodeA"), "First event must be in batch");
        assertTrue(json.contains("NodeB"), "Second event must be in batch");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String decompress(byte[] gzipBytes) throws IOException {
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
            return new String(gzis.readAllBytes());
        }
    }
}

