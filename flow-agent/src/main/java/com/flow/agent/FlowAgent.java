package com.flow.agent;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.config.ConfigLoader;
import com.flow.agent.context.FlowContext;
import com.flow.agent.filter.FilterChain;
import com.flow.agent.instrumentation.FlowTransformer;
import com.flow.agent.instrumentation.ProxyResolver;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.BatchAssembler;
import com.flow.agent.pipeline.EventRingBuffer;
import com.flow.agent.pipeline.FlowEventSink;
import com.flow.agent.sampling.AlwaysSampler;
import com.flow.agent.transport.CircuitBreaker;
import com.flow.agent.transport.HttpBatchSender;

import java.lang.instrument.Instrumentation;

/**
 * Flow Runtime Agent entry point.
 *
 * <p>Attach via: {@code -javaagent:flow-agent.jar}
 *
 * <p><strong>Golden Rule:</strong> This class MUST NEVER cause the customer application to
 * fail. Every path is wrapped in try-catch. If anything goes wrong during init, the JVM
 * continues normally — just without instrumentation.
 */
public class FlowAgent {

    private static volatile boolean initialized = false;

    /**
     * Called by the JVM before {@code main()}.
     * Must complete in &lt; 500 ms. Must NOT throw.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            if (initialized) return;
            initialized = true;

            // 1. Load configuration
            AgentConfig config = ConfigLoader.load(agentArgs);

            // 2. Kill switch
            if (!config.isEnabled()) {
                log("[flow-agent] Disabled via config. Exiting premain().");
                return;
            }

            // 3. Build filter chain
            FilterChain filterChain = FilterChain.from(config);

            // 4. Initialize FlowContext class loading
            FlowContext.init();

            // 5. Initialize ProxyResolver with package prefixes
            ProxyResolver.init(config.getPackages().getInclude());

            // 6. Initialize pipeline (ring buffer + sampler + batch assembler)
            EventRingBuffer ringBuffer = new EventRingBuffer(config.getPipeline().getBufferSize());
            FlowEventSink.init(ringBuffer, new AlwaysSampler());

            // 7. Initialize transport (HTTP sender + circuit breaker)
            CircuitBreaker circuitBreaker = new CircuitBreaker(config.getCircuitBreaker());
            HttpBatchSender sender = new HttpBatchSender(config.getServer(), circuitBreaker);

            // 8. Start batch assembler daemon thread
            BatchAssembler assembler = new BatchAssembler(
                    ringBuffer, sender, config.getPipeline(), config.getGraphId()
            );
            assembler.start(); // daemon thread

            // 9. Install ByteBuddy transformer
            FlowTransformer.install(instrumentation, filterChain, config);

            // 10. Start agent metrics logger
            AgentMetrics.startPeriodicLog(60); // every 60 seconds

            log("[flow-agent] Initialized. graphId=" + config.getGraphId()
                    + " packages=" + config.getPackages().getInclude());

        } catch (Throwable t) {
            // GOLDEN RULE: never crash the customer's app
            System.err.println("[flow-agent] Failed to initialize: " + t.getMessage());
        }
    }

    /**
     * For dynamic attach (Phase 6).
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}

