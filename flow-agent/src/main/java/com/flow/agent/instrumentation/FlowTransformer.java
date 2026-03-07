package com.flow.agent.instrumentation;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.filter.FilterChain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
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

