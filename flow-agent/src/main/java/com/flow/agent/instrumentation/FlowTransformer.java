package com.flow.agent.instrumentation;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.filter.FilterChain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

/**
 * Installs ByteBuddy class transformations for method-level tracing.
 *
 * <p>Only transforms classes that belong to customer packages (as configured in
 * {@code flow.packages.include}). Framework code is never touched.
 *
 * <p>If transformation of a class fails, it is skipped silently — it loads normally
 * and the customer app continues unaffected.
 */
public class FlowTransformer {

    public static void install(Instrumentation instrumentation,
                               FilterChain filterChain,
                               AgentConfig config) {

        new AgentBuilder.Default()
                // Retransform already-loaded classes (e.g., classes loaded before premain)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // Safety: log errors but never throw
                .with(new SafeTransformListener())
                // Safer transformations — do not change class file format
                .disableClassFormatChanges()

                // Only instrument classes matching the customer's package filter
                .type(typeDescription -> filterChain.shouldInstrumentClass(typeDescription.getName()))

                // Apply method-level tracing advice
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(MethodAdvice.class)
                                        .on(method -> filterChain.shouldInstrumentMethod(typeDescription, method))
                        )
                )

                .installOn(instrumentation);

        // ── Entry-point isolation (Spring MVC + Kafka) ──────────────────────
        // Force new trace context at HTTP/Kafka entry points to prevent context leaks
        // when the Spring controller method is outside the instrumented packages.
        // Uses string-based annotation names (not class references) so this works even
        // if Spring is NOT on the agent's classpath — ByteBuddy resolves on the
        // customer's classloader.
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()

                // Match Spring MVC controllers and Kafka listener classes
                .type(ElementMatchers.isAnnotatedWith(
                        ElementMatchers.named("org.springframework.web.bind.annotation.RestController")
                                .or(ElementMatchers.named("org.springframework.stereotype.Controller"))
                ))

                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(EntryPointAdvice.class)
                                        .on(ElementMatchers.isAnnotatedWith(
                                                ElementMatchers.named("org.springframework.web.bind.annotation.GetMapping")
                                                        .or(ElementMatchers.named("org.springframework.web.bind.annotation.PostMapping"))
                                                        .or(ElementMatchers.named("org.springframework.web.bind.annotation.PutMapping"))
                                                        .or(ElementMatchers.named("org.springframework.web.bind.annotation.DeleteMapping"))
                                                        .or(ElementMatchers.named("org.springframework.web.bind.annotation.PatchMapping"))
                                                        .or(ElementMatchers.named("org.springframework.web.bind.annotation.RequestMapping"))
                                                        .or(ElementMatchers.named("org.springframework.kafka.annotation.KafkaListener"))
                                        ))
                        )
                )

                .installOn(instrumentation);

        // ── Checkpoint SDK interception ──────────────────────────────────────
        // Intercept com.flow.sdk.Flow#checkpoint() calls to emit CHECKPOINT events.
        // Two-arg: checkpoint(String, Object)
        // Three-arg: checkpoint(String, Object, FlowCapture)
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()

                .type(ElementMatchers.named("com.flow.sdk.Flow"))

                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder
                                // Two-arg: checkpoint(String, Object)
                                .visit(Advice.to(CheckpointInterceptor.TwoArgAdvice.class)
                                        .on(ElementMatchers.named("checkpoint")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(2))))
                                // Three-arg: checkpoint(String, Object, FlowCapture)
                                .visit(Advice.to(CheckpointInterceptor.ThreeArgAdvice.class)
                                        .on(ElementMatchers.named("checkpoint")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(3))))
                )

                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — Java 11 HttpRequest.Builder ─────────
        // Intercept build() so we can mutate headers before the immutable HttpRequest is created.
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.named("java.net.http.HttpRequest$Builder")
                        .or(ElementMatchers.hasSuperType(
                                ElementMatchers.named("java.net.http.HttpRequest$Builder"))))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.BuilderAdvice.class)
                                .on(ElementMatchers.named("build"))))
                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — OkHttp Request.Builder ───────────────
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.named("okhttp3.Request$Builder"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.BuilderAdvice.class)
                                .on(ElementMatchers.named("build"))))
                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — Spring RestTemplate ──────────────────
        // Intercept ClientHttpRequest.execute() — the actual outgoing call point where
        // headers are still mutable via getHeaders().add(). All RestTemplate / WebClient
        // calls funnel through this interface method.
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.springframework.http.client.ClientHttpRequest")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.RestTemplateAdvice.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArguments(0)))))
                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — Spring WebClient (reactive) ──────────
        // WebClient builds an immutable ClientRequest via ClientRequest.Builder#build().
        // Intercept build() so we can inject headers while the builder is still mutable.
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.springframework.web.reactive.function.client.ClientRequest$Builder")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.BuilderAdvice.class)
                                .on(ElementMatchers.named("build")
                                        .and(ElementMatchers.takesArguments(0)))))
                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — Apache HttpClient 4/5 ────────────────
        // CloseableHttpClient.execute(HttpUriRequest, ...) — first arg is the mutable request.
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.apache.http.impl.client.CloseableHttpClient")
                                .or(ElementMatchers.named("org.apache.hc.client5.http.impl.classic.CloseableHttpClient"))))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.ExecuteAdvice.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArgument(0,
                                                ElementMatchers.hasSuperType(
                                                        ElementMatchers.named("org.apache.http.HttpRequest")
                                                                .or(ElementMatchers.named("org.apache.hc.core5.http.HttpRequest"))))))))
                .installOn(instrumentation);

        // ── Outgoing HTTP propagation — Feign Client ─────────────────────────
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new SafeTransformListener())
                .disableClassFormatChanges()
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("feign.Client")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OutgoingHttpAdvice.ExecuteAdvice.class)
                                .on(ElementMatchers.named("execute"))))
                .installOn(instrumentation);
    }

    /**
     * Safety listener — logs warnings, NEVER throws.
     * If a class fails to transform, it loads normally (uninstrumented).
     */
    private static class SafeTransformListener extends AgentBuilder.Listener.Adapter {

        @Override
        public void onError(String typeName,
                            ClassLoader classLoader,
                            JavaModule module,
                            boolean loaded,
                            Throwable throwable) {
            System.err.println("[flow-agent] WARN: Failed to transform " + typeName
                    + ": " + throwable.getMessage());
            // DO NOT rethrow — the class loads normally, uninstrumented
        }
    }
}

