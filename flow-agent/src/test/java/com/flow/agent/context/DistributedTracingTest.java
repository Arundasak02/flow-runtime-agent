package com.flow.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for distributed trace context extraction and injection.
 */
@DisplayName("Distributed Tracing")
class DistributedTracingTest {

    // ── TraceContextExtractor ─────────────────────────────────────────────────

    @Nested
    @DisplayName("TraceContextExtractor")
    class ExtractorTests {

        @Test
        @DisplayName("parses valid W3C traceparent header")
        void parsesValidTraceparent() {
            String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
            DistributedTraceContext ctx = TraceContextExtractor.parseTraceparent(traceparent);

            assertNotNull(ctx);
            assertEquals("w3c-traceparent", ctx.getSource());
            // 4bf92f3577b34da6a3ce929d0e0e4736 → UUID format
            assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", ctx.getTraceId());
            assertEquals("00f067aa0ba902b7", ctx.getParentSpanId());
        }

        @Test
        @DisplayName("rejects traceparent with too few parts")
        void rejectsTooFewParts() {
            assertNull(TraceContextExtractor.parseTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736"));
        }

        @Test
        @DisplayName("rejects all-zero traceId in traceparent")
        void rejectsAllZeroTraceId() {
            assertNull(TraceContextExtractor.parseTraceparent(
                    "00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        }

        @Test
        @DisplayName("rejects null traceparent")
        void rejectsNullTraceparent() {
            assertNull(TraceContextExtractor.parseTraceparent(null));
        }

        @Test
        @DisplayName("parses valid B3 single-header")
        void parsesValidB3Single() {
            // traceId-spanId-sampled
            DistributedTraceContext ctx = TraceContextExtractor.parseB3Single(
                    "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1");

            assertNotNull(ctx);
            assertEquals("b3-single", ctx.getSource());
            assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", ctx.getTraceId());
            assertEquals("00f067aa0ba902b7", ctx.getParentSpanId());
        }

        @Test
        @DisplayName("returns null for B3 deny-sampling shorthand '0'")
        void b3SingleDenyReturnsNull() {
            assertNull(TraceContextExtractor.parseB3Single("0"));
        }

        @Test
        @DisplayName("extracts Flow-native headers from header map")
        void extractsFlowNativeHeaders() {
            Map<String, String> headers = Map.of(
                    "X-Flow-Trace-Id", "my-trace-123",
                    "X-Flow-Span-Id",  "abcdef1234567890"
            );
            DistributedTraceContext ctx = TraceContextExtractor.extractFromHeaders(headers);

            assertNotNull(ctx);
            assertEquals("my-trace-123", ctx.getTraceId());
            assertEquals("abcdef1234567890", ctx.getParentSpanId());
            assertEquals("flow-native", ctx.getSource());
        }

        @Test
        @DisplayName("W3C traceparent takes priority over Flow-native headers")
        void w3cTakesPriorityOverFlowNative() {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            headers.put("X-Flow-Trace-Id", "should-be-ignored");

            DistributedTraceContext ctx = TraceContextExtractor.extractFromHeaders(headers);

            assertNotNull(ctx);
            assertEquals("w3c-traceparent", ctx.getSource());
            assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", ctx.getTraceId());
        }

        @Test
        @DisplayName("returns null for empty header map")
        void returnsNullForEmptyMap() {
            assertNull(TraceContextExtractor.extractFromHeaders(Map.of()));
        }

        @Test
        @DisplayName("handles case-insensitive header lookup")
        void caseInsensitiveHeaderLookup() {
            Map<String, String> headers = Map.of(
                    "x-flow-trace-id", "trace-abc",
                    "x-flow-span-id",  "span-001"
            );
            DistributedTraceContext ctx = TraceContextExtractor.extractFromHeaders(headers);

            assertNotNull(ctx);
            assertEquals("trace-abc", ctx.getTraceId());
        }
    }

    // ── TraceContextInjector ──────────────────────────────────────────────────

    @Nested
    @DisplayName("TraceContextInjector")
    class InjectorTests {

        @Test
        @DisplayName("builds all propagation headers from a FlowContext")
        void buildsAllHeaders() {
            FlowContext ctx = FlowContext.initFromRemote(null); // fresh trace
            try {
                String traceId  = ctx.getTraceId();
                String spanId   = "abcdef1234567890";
                Map<String, String> headers =
                        TraceContextInjector.buildHeaders(ctx, spanId, "order-service");

                // W3C traceparent present
                assertTrue(headers.containsKey("traceparent"),
                        "must contain traceparent");
                String traceparent = headers.get("traceparent");
                assertTrue(traceparent.startsWith("00-"),
                        "traceparent must start with version 00-");
                assertTrue(traceparent.endsWith("-01"),
                        "traceparent must end with sampled flag 01");
                // includes spanId
                assertTrue(traceparent.contains(spanId), "traceparent must embed spanId");

                // Flow-native headers
                assertEquals(traceId, headers.get("X-Flow-Trace-Id"));
                assertEquals(spanId,  headers.get("X-Flow-Span-Id"));

                // B3 single
                assertTrue(headers.containsKey("b3"), "must contain b3 header");

                // Service name
                assertEquals("order-service", headers.get("X-Flow-Service"));
            } finally {
                FlowContext.clear();
            }
        }

        @Test
        @DisplayName("returns empty map when FlowContext is null")
        void returnsEmptyMapForNullContext() {
            Map<String, String> headers = TraceContextInjector.buildHeaders(null, "span1", "svc");
            assertTrue(headers.isEmpty());
        }

        @Test
        @DisplayName("uuidToHex32 converts UUID to 32-char lowercase hex")
        void uuidToHex32Converts() {
            String uuid   = "4bf92f35-77b3-4da6-a3ce-929d0e0e4736";
            String hex32  = TraceContextInjector.uuidToHex32(uuid);
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", hex32);
            assertEquals(32, hex32.length());
        }

        @Test
        @DisplayName("uuidToHex32 handles null gracefully")
        void uuidToHex32HandlesNull() {
            String hex32 = TraceContextInjector.uuidToHex32(null);
            assertEquals("00000000000000000000000000000000", hex32);
        }
    }

    // ── FlowContext.initFromRemote() ──────────────────────────────────────────

    @Nested
    @DisplayName("FlowContext.initFromRemote")
    class FlowContextRemoteTests {

        @Test
        @DisplayName("continues upstream trace when remote context is present")
        void continuesUpstreamTrace() {
            DistributedTraceContext remote =
                    new DistributedTraceContext("upstream-trace-id", "remote-span-abc", "w3c-traceparent");
            FlowContext ctx = FlowContext.initFromRemote(remote);
            try {
                assertEquals("upstream-trace-id", ctx.getTraceId());
                assertEquals("remote-span-abc", ctx.getRemoteParentSpanId());
            } finally {
                FlowContext.clear();
            }
        }

        @Test
        @DisplayName("starts fresh trace when remote context is null")
        void startsFreshTraceWhenNull() {
            FlowContext ctx = FlowContext.initFromRemote(null);
            try {
                assertNotNull(ctx.getTraceId());
                assertNull(ctx.getRemoteParentSpanId());
            } finally {
                FlowContext.clear();
            }
        }

        @Test
        @DisplayName("root span gets remoteParentSpanId as its parentSpanId")
        void rootSpanLinksToRemote() {
            DistributedTraceContext remote =
                    new DistributedTraceContext("trace-xyz", "remote-span-001", "flow-native");
            FlowContext ctx = FlowContext.initFromRemote(remote);
            try {
                // Before any spans are pushed the stack is empty — remoteParentSpanId is returned
                assertNull(ctx.currentSpanId(), "stack is empty, no local span yet");
                assertEquals("remote-span-001", ctx.getRemoteParentSpanId());

                // Simulate what MethodAdvice does for the root span
                String rootParent = ctx.isSpanStackEmpty()
                        ? ctx.getRemoteParentSpanId()
                        : ctx.currentSpanId();
                assertEquals("remote-span-001", rootParent);
            } finally {
                FlowContext.clear();
            }
        }
    }
}

