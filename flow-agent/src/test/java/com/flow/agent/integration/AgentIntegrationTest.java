package com.flow.agent.integration;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.context.FlowContext;
import com.flow.agent.instrumentation.NodeIdBuilder;
import com.flow.agent.instrumentation.ProxyResolver;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.EventRingBuffer;
import com.flow.agent.pipeline.FlowEventSink;
import com.flow.agent.pipeline.RuntimeEvent;
import com.flow.agent.sampling.AlwaysSampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test exercising the full event pipeline end-to-end:
 * FlowContext → NodeIdBuilder → FlowEventSink → EventRingBuffer → drain.
 *
 * <p>Full agent-attach integration (with a sample JAR and mock FCS HTTP server)
 * is deferred to CI — see Section 10.3 of the implementation guide.
 */
class AgentIntegrationTest {

    private EventRingBuffer ringBuffer;

    // ── Sample customer class ────────────────────────────────────────────────

    public static class OrderService {
        public String placeOrder(String orderId) { return orderId; }
        public void validateCart(String cartId) {}
    }

    @BeforeEach
    void setup() {
        FlowContext.clear();
        ProxyResolver.init(List.of("com.flow.agent"));

        ringBuffer = new EventRingBuffer(256);
        FlowEventSink.init(ringBuffer, new AlwaysSampler());
    }

    @AfterEach
    void cleanup() {
        FlowContext.clear();
    }

    // ── Pipeline integration ─────────────────────────────────────────────────

    @Test
    void fullPipeline_emitEnterAndExit_eventsDrainCorrectly() throws Exception {
        Method method = OrderService.class.getMethod("placeOrder", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, method);

        FlowContext ctx = FlowContext.getOrInit();
        String traceId = ctx.getTraceId();

        // Simulate METHOD_ENTER
        FlowEventSink.emit(traceId, "span-1", null, nodeId,
                "METHOD_ENTER", System.currentTimeMillis(), 0L, null);

        // Simulate METHOD_EXIT
        FlowEventSink.emit(traceId, "span-1", null, nodeId,
                "METHOD_EXIT", System.currentTimeMillis(), 42L, null);

        // Drain and verify
        List<RuntimeEvent> events = new ArrayList<>();
        ringBuffer.drainTo(events, 100);

        assertEquals(2, events.size());

        RuntimeEvent enter = events.get(0);
        assertEquals("METHOD_ENTER", enter.getType());
        assertEquals(traceId, enter.getTraceId());
        assertEquals(nodeId, enter.getNodeId());

        RuntimeEvent exit = events.get(1);
        assertEquals("METHOD_EXIT", exit.getType());
        assertEquals(42L, exit.getDurationMs());
    }

    @Test
    void nodeIdFormat_matchesExpectedContract() throws Exception {
        Method m = OrderService.class.getMethod("placeOrder", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);

        // Format: {FQCN}#{methodName}({paramTypes}):{returnType}
        assertTrue(nodeId.contains("#placeOrder("), "Must contain method name");
        assertTrue(nodeId.endsWith("):String"), "Must end with return type");
        assertTrue(nodeId.contains("String"), "Param type must be simplified");
    }

    @Test
    void flowContext_clearedAfterRequest_noTraceCorruption() {
        // Request A
        FlowContext ctxA = FlowContext.getOrInit();
        String traceIdA = ctxA.getTraceId();
        FlowContext.clear();

        // Request B — same thread, should get new traceId
        FlowContext ctxB = FlowContext.getOrInit();
        String traceIdB = ctxB.getTraceId();
        FlowContext.clear();

        assertNotEquals(traceIdA, traceIdB,
                "After clear(), next request must get a fresh traceId");
    }

    @Test
    void errorEvent_includesExceptionType() {
        FlowContext ctx = FlowContext.getOrInit();

        FlowEventSink.emit(ctx.getTraceId(), "span-1", null,
                "com.example.Service#fail():void",
                "ERROR", System.currentTimeMillis(), 5L,
                "java.lang.IllegalArgumentException");

        List<RuntimeEvent> events = new ArrayList<>();
        ringBuffer.drainTo(events, 100);

        assertEquals(1, events.size());
        RuntimeEvent event = events.get(0);
        assertEquals("ERROR", event.getType());
        assertEquals("java.lang.IllegalArgumentException", event.getErrorType());
    }

    @Test
    void ringBufferOverflow_doesNotBlockApplicationThread() {
        EventRingBuffer smallBuffer = new EventRingBuffer(2);
        FlowEventSink.init(smallBuffer, new AlwaysSampler());

        FlowContext ctx = FlowContext.getOrInit();

        // Fill buffer
        FlowEventSink.emit(ctx.getTraceId(), "s1", null, "n1", "METHOD_ENTER", 0, 0, null);
        FlowEventSink.emit(ctx.getTraceId(), "s2", null, "n2", "METHOD_ENTER", 0, 0, null);

        // This should NOT block — event is dropped silently
        long start = System.nanoTime();
        FlowEventSink.emit(ctx.getTraceId(), "s3", null, "n3", "METHOD_ENTER", 0, 0, null);
        long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < 10_000_000L,
                "Overflow emit must be non-blocking; took " + elapsed + " ns");

        // Restore original buffer
        FlowEventSink.init(ringBuffer, new AlwaysSampler());
    }
}

