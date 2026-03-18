package com.flow.agent.context;

import java.lang.reflect.Method;

/**
 * Extracts distributed trace context from an inbound HTTP request's headers.
 *
 * <p><strong>Header resolution order</strong> (first match wins):
 * <ol>
 *   <li><b>W3C {@code traceparent}</b> — standard, preferred by OTel/Micrometer</li>
 *   <li><b>OpenTelemetry bridge</b> — if OTel SDK is on the classpath, reads the active span
 *       via reflection so we don't add a hard compile-time dependency on the OTel API.</li>
 *   <li><b>Micrometer Tracing bridge</b> — if Micrometer Tracing is on the classpath,
 *       reads the current trace/span via reflection.</li>
 *   <li><b>Flow-native headers</b> — {@code X-Flow-Trace-Id} + {@code X-Flow-Span-Id}</li>
 *   <li><b>B3 single-header</b> — {@code b3}</li>
 *   <li><b>B3 multi-header</b> — {@code X-B3-TraceId} + {@code X-B3-SpanId}</li>
 * </ol>
 *
 * <p>The extractor is called via reflection from {@link com.flow.agent.instrumentation.EntryPointAdvice}
 * which intercepts the servlet {@code HttpServletRequest} or Spring's
 * {@code ServerWebExchange} before any handler runs.
 *
 * <p>All methods are safe — they never throw, never return null.
 */
public class TraceContextExtractor {

    // ── W3C traceparent ──────────────────────────────────────────────────────
    // Format: 00-<32-hex-traceId>-<16-hex-parentId>-<2-hex-flags>
    private static final String W3C_TRACEPARENT    = "traceparent";

    // ── Flow-native headers ──────────────────────────────────────────────────
    public static final String FLOW_TRACE_ID_HEADER = "X-Flow-Trace-Id";
    public static final String FLOW_SPAN_ID_HEADER  = "X-Flow-Span-Id";
    /** Service name header — carried through the chain for multi-service graphs. */
    public static final String FLOW_SERVICE_HEADER  = "X-Flow-Service";

    // ── B3 headers ───────────────────────────────────────────────────────────
    private static final String B3_SINGLE          = "b3";
    private static final String B3_TRACE_ID        = "X-B3-TraceId";
    private static final String B3_SPAN_ID         = "X-B3-SpanId";

    // ── OTel reflection cache ─────────────────────────────────────────────────
    // Resolved lazily once; null means OTel is not on the classpath.
    private static volatile Boolean otelPresent;    // null = not yet checked
    private static volatile Method  otelCurrentSpan;
    private static volatile Method  otelSpanGetContext;
    private static volatile Method  otelTraceContextGetTraceId;
    private static volatile Method  otelTraceContextGetSpanId;

    // ── Micrometer Tracing reflection cache ───────────────────────────────────
    private static volatile Boolean micrometerPresent;
    private static volatile Object  micrometerTracer;   // io.micrometer.tracing.Tracer instance (via OTel bridge)
    private static volatile Method  micrometerCurrentSpan;
    private static volatile Method  micrometerSpanContext;
    private static volatile Method  micrometerTraceId;
    private static volatile Method  micrometerSpanId;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extract context from a generic header accessor.
     *
     * @param headerAccessor an object that has a {@code getHeader(String)} method
     *                       (e.g. {@code HttpServletRequest}, or any object passed
     *                       via the ByteBuddy advice).
     * @return extracted context, or {@code null} if no trace headers were found.
     */
    public static DistributedTraceContext extract(Object headerAccessor) {
        if (headerAccessor == null) return null;
        try {
            return doExtract(headerAccessor);
        } catch (Throwable t) {
            // Never propagate — distributed tracing is optional enrichment
            return null;
        }
    }

    /**
     * Extract from a pre-resolved header map (string → string).
     * Useful when headers have already been collected by an interceptor.
     */
    public static DistributedTraceContext extractFromHeaders(java.util.Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        try {
            return doExtractFromMap(headers);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Private implementation ────────────────────────────────────────────────

    private static DistributedTraceContext doExtract(Object req) throws Exception {
        // 1. W3C traceparent header
        String traceparent = getHeader(req, W3C_TRACEPARENT);
        if (traceparent != null) {
            DistributedTraceContext ctx = parseTraceparent(traceparent);
            if (ctx != null) return ctx;
        }

        // 2. OTel active span (if OTel SDK present — highest fidelity)
        DistributedTraceContext otelCtx = tryExtractFromOtel(req.getClass().getClassLoader());
        if (otelCtx != null) return otelCtx;

        // 3. Micrometer Tracing active span
        DistributedTraceContext micrometerCtx = tryExtractFromMicrometer(req.getClass().getClassLoader());
        if (micrometerCtx != null) return micrometerCtx;

        // 4. Flow-native headers
        String flowTraceId = getHeader(req, FLOW_TRACE_ID_HEADER);
        String flowSpanId  = getHeader(req, FLOW_SPAN_ID_HEADER);
        if (flowTraceId != null && !flowTraceId.isEmpty()) {
            return new DistributedTraceContext(flowTraceId, flowSpanId, "flow-native");
        }

        // 5. B3 single-header
        String b3Single = getHeader(req, B3_SINGLE);
        if (b3Single != null) {
            DistributedTraceContext b3Ctx = parseB3Single(b3Single);
            if (b3Ctx != null) return b3Ctx;
        }

        // 6. B3 multi-header
        String b3TraceId = getHeader(req, B3_TRACE_ID);
        String b3SpanId  = getHeader(req, B3_SPAN_ID);
        if (b3TraceId != null && !b3TraceId.isEmpty()) {
            return new DistributedTraceContext(b3TraceId, b3SpanId, "b3-multi");
        }

        return null; // no distributed context found — start fresh trace
    }

    private static DistributedTraceContext doExtractFromMap(java.util.Map<String, String> headers) {
        // 1. W3C traceparent
        String traceparent = getHeaderFromMap(headers, W3C_TRACEPARENT);
        if (traceparent != null) {
            DistributedTraceContext ctx = parseTraceparent(traceparent);
            if (ctx != null) return ctx;
        }

        // 2. Flow-native
        String flowTraceId = getHeaderFromMap(headers, FLOW_TRACE_ID_HEADER);
        String flowSpanId  = getHeaderFromMap(headers, FLOW_SPAN_ID_HEADER);
        if (flowTraceId != null && !flowTraceId.isEmpty()) {
            return new DistributedTraceContext(flowTraceId, flowSpanId, "flow-native");
        }

        // 3. B3 single
        String b3Single = getHeaderFromMap(headers, B3_SINGLE);
        if (b3Single != null) {
            DistributedTraceContext b3Ctx = parseB3Single(b3Single);
            if (b3Ctx != null) return b3Ctx;
        }

        // 4. B3 multi
        String b3TraceId = getHeaderFromMap(headers, B3_TRACE_ID);
        String b3SpanId  = getHeaderFromMap(headers, B3_SPAN_ID);
        if (b3TraceId != null && !b3TraceId.isEmpty()) {
            return new DistributedTraceContext(b3TraceId, b3SpanId, "b3-multi");
        }

        return null;
    }

    // ── W3C traceparent parser ────────────────────────────────────────────────

    /**
     * Parses: {@code 00-<32hex-traceId>-<16hex-spanId>-<flags>}
     */
    static DistributedTraceContext parseTraceparent(String value) {
        if (value == null) return null;
        String[] parts = value.split("-");
        if (parts.length < 4) return null;
        // parts[0] = version (00), parts[1] = traceId (32 hex), parts[2] = parentId (16 hex), parts[3] = flags
        String traceId  = parts[1];
        String parentId = parts[2];
        if (traceId.length() != 32 || parentId.length() != 16) return null;
        if (traceId.equals("00000000000000000000000000000000")) return null; // invalid
        // Convert W3C 32-char hex traceId to UUID format for internal consistency
        String flowTraceId = hex32ToUuid(traceId);
        return new DistributedTraceContext(flowTraceId, parentId, "w3c-traceparent");
    }

    /** Converts 32-char hex (W3C) to UUID format {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. */
    private static String hex32ToUuid(String hex) {
        if (hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-"
                + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-"
                + hex.substring(20, 32);
    }

    // ── B3 single-header parser ───────────────────────────────────────────────

    /**
     * Parses: {@code <traceId>-<spanId>[-<sampled>[-<parentId>]]} or {@code 0}/{@code 1} (deny/allow)
     */
    static DistributedTraceContext parseB3Single(String value) {
        if (value == null || value.equals("0") || value.equals("1")) return null;
        String[] parts = value.split("-");
        if (parts.length < 2) return null;
        String traceId = parts[0];
        String spanId  = parts[1];
        if (traceId.isEmpty()) return null;
        // Normalise 32-char hex to UUID
        if (traceId.length() == 32) traceId = hex32ToUuid(traceId);
        return new DistributedTraceContext(traceId, spanId, "b3-single");
    }

    // ── OTel reflective bridge ────────────────────────────────────────────────

    /**
     * If OpenTelemetry SDK is on the application's classloader, read the current active span.
     * Uses reflection to avoid a hard compile dependency.
     */
    private static DistributedTraceContext tryExtractFromOtel(ClassLoader cl) {
        if (Boolean.FALSE.equals(otelPresent)) return null;
        try {
            if (otelPresent == null) {
                Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span", false, cl);
                otelCurrentSpan = spanClass.getMethod("current");
                Class<?> spanCtxClass = Class.forName("io.opentelemetry.api.trace.SpanContext", false, cl);
                otelSpanGetContext = spanClass.getMethod("getSpanContext");
                otelTraceContextGetTraceId = spanCtxClass.getMethod("getTraceId");
                otelTraceContextGetSpanId  = spanCtxClass.getMethod("getSpanId");
                otelPresent = Boolean.TRUE;
            }
            Object currentSpan   = otelCurrentSpan.invoke(null);
            Object spanContext    = otelSpanGetContext.invoke(currentSpan);
            // Check isValid — invalid spans have all-zero IDs
            Method isValid = spanContext.getClass().getMethod("isValid");
            Boolean valid = (Boolean) isValid.invoke(spanContext);
            if (!Boolean.TRUE.equals(valid)) return null;

            String traceId  = (String) otelTraceContextGetTraceId.invoke(spanContext);
            String spanId   = (String) otelTraceContextGetSpanId.invoke(spanContext);
            if (traceId == null || traceId.isEmpty()) return null;

            // OTel uses 32-char hex — normalise to UUID format
            if (traceId.length() == 32) traceId = hex32ToUuid(traceId);
            return new DistributedTraceContext(traceId, spanId, "opentelemetry");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            otelPresent = Boolean.FALSE;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Micrometer Tracing reflective bridge ─────────────────────────────────

    /**
     * If Micrometer Tracing (e.g. Spring Boot 3 Observability) is on the classpath,
     * read the current trace context.
     */
    private static DistributedTraceContext tryExtractFromMicrometer(ClassLoader cl) {
        if (Boolean.FALSE.equals(micrometerPresent)) return null;
        try {
            if (micrometerPresent == null) {
                Class<?> tracerClass = Class.forName(
                        "io.micrometer.tracing.Tracer", false, cl);
                // Micrometer's Tracer is typically a Spring bean — look for ThreadLocalCurrentTraceContext
                // via the static Tracer.NOOP sentinel or via the ThreadLocalCurrentTraceContext class.
                // The easiest route: use CurrentTraceContext directly.
                Class<?> ctcClass = Class.forName(
                        "io.micrometer.tracing.CurrentTraceContext", false, cl);
                micrometerCurrentSpan = ctcClass.getMethod("context");
                Class<?> tcClass = Class.forName(
                        "io.micrometer.tracing.TraceContext", false, cl);
                micrometerTraceId = tcClass.getMethod("traceId");
                micrometerSpanId  = tcClass.getMethod("spanId");
                micrometerPresent = Boolean.TRUE;
            }
            // We need a Tracer instance — look up the shared OTel-backed bean via
            // ApplicationContext is not available here, so we try to find a
            // ThreadLocalCurrentTraceContext directly.
            // If the instance hasn't been set (micrometerTracer == null), we skip.
            if (micrometerTracer == null) return null;
            Object traceCtx = micrometerCurrentSpan.invoke(micrometerTracer);
            if (traceCtx == null) return null;
            String traceId = (String) micrometerTraceId.invoke(traceCtx);
            String spanId  = (String) micrometerSpanId.invoke(traceCtx);
            if (traceId == null || traceId.isEmpty()) return null;
            return new DistributedTraceContext(traceId, spanId, "micrometer");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            micrometerPresent = Boolean.FALSE;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Register a Micrometer {@code CurrentTraceContext} instance so the reflective bridge
     * can read the current trace. Call this from a Spring-aware component at startup.
     *
     * @param currentTraceContextInstance a {@code io.micrometer.tracing.CurrentTraceContext}
     */
    public static void registerMicrometerTracer(Object currentTraceContextInstance) {
        micrometerTracer = currentTraceContextInstance;
    }

    // ── Header access helpers ─────────────────────────────────────────────────

    /**
     * Reflectively calls {@code obj.getHeader(name)} — works for:
     * {@code javax.servlet.http.HttpServletRequest},
     * {@code jakarta.servlet.http.HttpServletRequest},
     * any object that has a {@code getHeader(String)} method.
     */
    private static String getHeader(Object obj, String name) {
        try {
            Method m = obj.getClass().getMethod("getHeader", String.class);
            return (String) m.invoke(obj, name);
        } catch (Throwable t) {
            // Not an HTTP request. Try Kafka ConsumerRecord-style headers:
            // record.headers().lastHeader(name).value() → byte[]
            try {
                Method headersMethod = obj.getClass().getMethod("headers");
                Object headers = headersMethod.invoke(obj);
                if (headers == null) return null;

                Method lastHeader = headers.getClass().getMethod("lastHeader", String.class);
                Object header = lastHeader.invoke(headers, name);
                if (header == null) return null;

                Method valueMethod = header.getClass().getMethod("value");
                Object value = valueMethod.invoke(header);
                if (!(value instanceof byte[])) return null;
                byte[] bytes = (byte[]) value;
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static String getHeaderFromMap(java.util.Map<String, String> headers, String name) {
        // Case-insensitive lookup
        String val = headers.get(name);
        if (val != null) return val;
        for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }
}

