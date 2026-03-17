package com.flow.agent.instrumentation;

import com.flow.agent.config.AgentConfigHolder;
import com.flow.agent.context.FlowContext;
import com.flow.agent.context.TraceContextInjector;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for outgoing HTTP calls — injects distributed trace propagation headers.
 *
 * <p>Installed on several outgoing HTTP client "builder" types so that the current
 * {@link FlowContext} trace-id is forwarded to downstream services:
 * <ul>
 *   <li>{@code java.net.http.HttpRequest.Builder#build()} (Java 11+)</li>
 *   <li>{@code org.springframework.web.client.RestTemplate} execute/exchange</li>
 *   <li>{@code org.springframework.web.reactive.function.client.WebClient.RequestBodySpec#retrieve()}</li>
 *   <li>{@code org.apache.http.impl.client.CloseableHttpClient#execute(...)}</li>
 *   <li>{@code okhttp3.OkHttpClient#newCall(...)}</li>
 *   <li>{@code feign.Client#execute(...)}</li>
 * </ul>
 *
 * <p>Headers injected (always):
 * <ul>
 *   <li>W3C {@code traceparent}: {@code 00-<traceId32hex>-<spanId16hex>-01}</li>
 *   <li>{@code X-Flow-Trace-Id}: UUID traceId</li>
 *   <li>{@code X-Flow-Span-Id}: current span's hex spanId</li>
 *   <li>B3 single {@code b3}: {@code <traceId>-<spanId>-1}</li>
 *   <li>{@code X-Flow-Service}: graphId / service name (from agent config)</li>
 * </ul>
 *
 * <p>This class contains two inner advice types because different HTTP clients expose
 * headers at different interception points:
 * <ul>
 *   <li>{@link BuilderAdvice} — intercepts at {@code build()} on builder objects
 *       (Java HttpRequest.Builder, OkHttp Request.Builder).</li>
 *   <li>{@link ExecuteAdvice} — intercepts at {@code execute()} on client objects
 *       where header mutation must happen before the call, not during build.
 *       This variant is used for Apache HttpClient, RestTemplate, Feign.</li>
 * </ul>
 *
 * <p><strong>Safety:</strong> all advice code is wrapped in try-catch. Header injection
 * failure never propagates to customer code.
 */
public class OutgoingHttpAdvice {

    /**
     * Advice for builder-pattern HTTP clients (Java 11 HttpRequest.Builder, OkHttp).
     * Injects headers into the builder's {@code header()} method before {@code build()} is called.
     *
     * <p>Intercept point: {@code @OnMethodEnter} of {@code build()} — at this point the builder
     * still accepts header mutations.
     */
    public static class BuilderAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onBuild(@Advice.This Object builder) {
            try {
                FlowContext ctx = FlowContext.current();
                if (ctx == null) return;

                String currentSpanId = ctx.currentSpanId();
                String serviceName   = AgentConfigHolder.getGraphId();

                // Inject all propagation headers into the builder
                TraceContextInjector.inject(builder, ctx, currentSpanId, serviceName);
            } catch (Throwable t) {
                // GOLDEN RULE: never propagate
            }
        }
    }

    /**
     * Advice for mutable-request HTTP clients (Apache HttpClient, Feign Request).
     * Injects headers by reflectively calling {@code addHeader(name, value)} on the
     * first argument (the mutable HTTP request object).
     *
     * <p>Intercept point: {@code @OnMethodEnter} of {@code execute(HttpRequest, ...)}
     */
    public static class ExecuteAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onExecute(@Advice.Argument(value = 0, optional = true) Object request) {
            try {
                if (request == null) return;
                FlowContext ctx = FlowContext.current();
                if (ctx == null) return;

                String currentSpanId = ctx.currentSpanId();
                String serviceName   = AgentConfigHolder.getGraphId();

                TraceContextInjector.inject(request, ctx, currentSpanId, serviceName);
            } catch (Throwable t) {
                // GOLDEN RULE: never propagate
            }
        }
    }

    /**
     * Advice for Spring RestTemplate / WebClient.
     *
     * <p>RestTemplate funnels all calls through
     * {@code ClientHttpRequest.execute()} — at that point headers are still mutable
     * via {@code getHeaders().add(name, value)}.
     * We intercept {@code execute()} on the {@code ClientHttpRequest} instance itself
     * ({@code @Advice.This}) and inject via its {@code HttpHeaders} accessor.
     *
     * <p>Intercept point: {@code @OnMethodEnter} of
     * {@code org.springframework.http.client.ClientHttpRequest#execute()}
     */
    public static class RestTemplateAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onExecute(@Advice.This Object clientHttpRequest) {
            try {
                FlowContext ctx = FlowContext.current();
                if (ctx == null) return;

                String currentSpanId = ctx.currentSpanId();
                String serviceName   = AgentConfigHolder.getGraphId();

                // ClientHttpRequest exposes getHeaders() → HttpHeaders → add(name, value)
                java.lang.reflect.Method getHeaders =
                        clientHttpRequest.getClass().getMethod("getHeaders");
                Object httpHeaders = getHeaders.invoke(clientHttpRequest);

                java.util.Map<String, String> propagation =
                        TraceContextInjector.buildHeaders(ctx, currentSpanId, serviceName);

                java.lang.reflect.Method add =
                        httpHeaders.getClass().getMethod("add", String.class, String.class);
                for (java.util.Map.Entry<String, String> e : propagation.entrySet()) {
                    add.invoke(httpHeaders, e.getKey(), e.getValue());
                }
            } catch (Throwable t) {
                // GOLDEN RULE: never propagate
            }
        }
    }
}

