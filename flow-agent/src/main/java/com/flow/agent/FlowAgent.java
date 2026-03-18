package com.flow.agent;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.config.AgentConfigHolder;
import com.flow.agent.config.ConfigLoader;
import com.flow.agent.context.FlowContext;
import com.flow.agent.filter.FilterChain;
import com.flow.agent.graph.GraphLoader;
import com.flow.agent.graph.LoadedGraph;
import com.flow.agent.graph.StaticGraphPublisher;
import com.flow.agent.instrumentation.CheckpointInterceptor;
import com.flow.agent.instrumentation.FlowTransformer;
import com.flow.agent.instrumentation.ProxyResolver;
import com.flow.agent.monitor.AgentLogger;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.BatchAssembler;
import com.flow.agent.pipeline.EventRingBuffer;
import com.flow.agent.pipeline.FlowEventSink;
import com.flow.agent.sampling.AlwaysSampler;
import com.flow.agent.transport.CircuitBreaker;
import com.flow.agent.transport.HttpBatchSender;

import java.lang.instrument.Instrumentation;
import java.util.Optional;

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
                AgentLogger.info("Disabled via config — instrumentation skipped.");
                return;
            }

            // 3. Build filter chain
            FilterChain filterChain = FilterChain.from(config);

            // 4. Initialize FlowContext class loading
            FlowContext.init();

            // 4a. Initialize static config holder (used by advice code for service name / graphId)
            AgentConfigHolder.init(config);

            // 5. Initialize ProxyResolver with package prefixes
            ProxyResolver.init(config.getPackages().getInclude());

            // 6. Initialize pipeline (ring buffer + sampler + batch assembler)
            EventRingBuffer ringBuffer = new EventRingBuffer(config.getPipeline().getBufferSize());
            FlowEventSink.init(ringBuffer, new AlwaysSampler());

            // 7. Initialize transport (HTTP sender + circuit breaker)
            CircuitBreaker circuitBreaker = new CircuitBreaker(config.getCircuitBreaker());
            HttpBatchSender sender = new HttpBatchSender(config.getServer(), circuitBreaker);

            // 7a. Optionally auto-publish static graph from application classpath.
            maybePublishStaticGraph(config, circuitBreaker);

            // 8. Start batch assembler daemon thread
            BatchAssembler assembler = new BatchAssembler(
                    ringBuffer, sender, config.getPipeline(), config.getGraphId()
            );
            assembler.start(); // daemon thread

            // 9. Initialize checkpoint interceptor (PII-safe object extraction)
            CheckpointInterceptor.init(config.getCapture());

            // 10. Install ByteBuddy transformer
            FlowTransformer.install(instrumentation, filterChain, config);

            // 11. Start agent metrics logger
            AgentMetrics.startPeriodicLog(60); // every 60 seconds

            AgentLogger.info("Initialized. graphId=" + config.getGraphId()
                    + " serviceName=" + config.getServiceName()
                    + " packages=" + config.getPackages().getInclude());

        } catch (Throwable t) {
            // GOLDEN RULE: never crash the customer's app
            AgentLogger.error("Failed to initialize — agent is disabled", t);
        }
    }

    /**
     * For dynamic attach (Phase 6).
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }

    private static void maybePublishStaticGraph(AgentConfig config, CircuitBreaker circuitBreaker) {
        if (!config.getGraph().isAutoPublish()) {
            AgentLogger.info("Static graph auto-publish disabled.");
            return;
        }

        long start = System.nanoTime();
        GraphLoader loader = new GraphLoader(config.getGraph().getClasspath());
        Optional<LoadedGraph> loaded = loader.load();
        if (loaded.isEmpty()) {
            return;
        }

        LoadedGraph graph = loaded.get();
        if (!config.getGraphId().equals(graph.graphId())) {
            AgentLogger.warn("Loaded graphId (" + graph.graphId() + ") differs from flow.graph-id ("
                    + config.getGraphId() + "). Runtime events use flow.graph-id.");
        }

        StaticGraphPublisher publisher = new StaticGraphPublisher(
                config.getServer(),
                circuitBreaker,
                config.getGraph().isDedup()
        );
        publisher.publishAsync(graph);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs > 50) {
            AgentLogger.warn("Static graph load took " + elapsedMs + "ms (target <= 50ms).");
        } else {
            AgentLogger.debug(() -> "Static graph load took " + elapsedMs + "ms.");
        }
    }
}

