package com.flow.agent.context;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects distributed trace headers into outgoing HTTP requests.
 *
 * <p>Called from ByteBuddy advice intercepting outgoing HTTP clients:
 * <ul>
 *   <li>Java 11+ {@code java.net.http.HttpRequest.Builder}</li>
 *   <li>Spring {@code RestTemplate} (via {@code ClientHttpRequestInterceptor})</li>
 *   <li>Apache HttpClient 4/5</li>
 *   <li>OkHttp</li>
 *   <li>Feign clients (via request interceptors)</li>
 * </ul>
 *
 * <p>Always emits:
 * <ul>
 *   <li>W3C {@code traceparent} — universally supported</li>
 *   <li>{@code X-Flow-Trace-Id} / {@code X-Flow-Span-Id} — Flow-native</li>
 *   <li>B3 single-header — for Zipkin/Brave compatibility</li>
 * </ul>
 *
 * <p>All methods are safe — they never throw.
 */
public class TraceContextInjector {

    // W3C version constant
    private static final String W3C_VERSION = "00";
    // Sampled flag — always sample for Flow tracing (we use our own sampler upstream)
    private static final String W3C_FLAGS   = "01";

    private TraceContextInjector() {}

    /**
     * Build the map of propagation headers to inject into an outgoing request.
     *
     * @param ctx       the current {@link FlowContext} (caller's context)
     * @param spanId    the span ID of the current outgoing call (will be the remote's parentSpanId)
     * @param serviceName optional service name to include in {@code X-Flow-Service}
     * @return immutable map of header name → value; empty if ctx is null.
     */
    public static Map<String, String> buildHeaders(FlowContext ctx, String spanId, String serviceName) {
        if (ctx == null) return Collections.emptyMap();
        try {
            String traceId = ctx.getTraceId();
            Map<String, String> headers = new LinkedHashMap<>(6);

            // 1. W3C traceparent
            String w3cTraceId  = uuidToHex32(traceId);
            String w3cParentId = spanId != null ? spanId : "0000000000000000";
            headers.put("traceparent",
                    W3C_VERSION + "-" + w3cTraceId + "-" + w3cParentId + "-" + W3C_FLAGS);

            // 2. Flow-native headers
            headers.put(TraceContextExtractor.FLOW_TRACE_ID_HEADER, traceId);
            if (spanId != null) {
                headers.put(TraceContextExtractor.FLOW_SPAN_ID_HEADER, spanId);
            }

            // 3. B3 single-header (Zipkin/Brave compatibility)
            headers.put("b3", w3cTraceId + "-" + w3cParentId + "-1");

            // 4. Service-name header (for multi-service graph correlation)
            if (serviceName != null && !serviceName.isEmpty()) {
                headers.put(TraceContextExtractor.FLOW_SERVICE_HEADER, serviceName);
            }

            return Collections.unmodifiableMap(headers);
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    /**
     * Inject headers into any object that has a
     * {@code header(String, String)} or {@code addHeader(String, String)} method.
     * Works reflectively for HttpRequest.Builder, Apache HttpRequest, OkHttp Request.Builder, etc.
     *
     * @param builder     the request builder / mutable request object
     * @param ctx         current flow context
     * @param spanId      outgoing span ID
     * @param serviceName optional service name
     */
    public static void inject(Object builder, FlowContext ctx, String spanId, String serviceName) {
        if (builder == null || ctx == null) return;
        try {
            Map<String, String> headers = buildHeaders(ctx, spanId, serviceName);
            // Try `header(String, String)` first (Java HttpRequest.Builder, OkHttp, Feign)
            Method headerMethod = findMethod(builder, "header", String.class, String.class);
            if (headerMethod == null) {
                // Fallback: `addHeader(String, String)` (Apache HttpClient, Spring MockHttpServletRequest)
                headerMethod = findMethod(builder, "addHeader", String.class, String.class);
            }
            if (headerMethod == null) return;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                headerMethod.invoke(builder, entry.getKey(), entry.getValue());
            }
        } catch (Throwable t) {
            // Never propagate — header injection is best-effort
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Convert a UUID string ({@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}) to 32-char lowercase hex
     * as required by W3C traceparent and B3 formats.
     */
    static String uuidToHex32(String uuid) {
        if (uuid == null) return "00000000000000000000000000000000";
        // If already 32 chars (no dashes), return as-is
        if (uuid.length() == 32 && !uuid.contains("-")) return uuid.toLowerCase();
        // Strip dashes
        String stripped = uuid.replace("-", "");
        if (stripped.length() == 32) return stripped.toLowerCase();
        // Pad or truncate to 32 chars
        if (stripped.length() < 32) {
            return String.format("%-32s", stripped).replace(' ', '0').toLowerCase();
        }
        return stripped.substring(0, 32).toLowerCase();
    }

    private static Method findMethod(Object obj, String name, Class<?>... paramTypes) {
        try {
            return obj.getClass().getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}

