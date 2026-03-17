package com.flow.agent.config;

/**
 * Static holder that makes key agent configuration values available to ByteBuddy advice
 * classes, which are inlined at class-load time and cannot hold instance references.
 *
 * <p>Initialised once during {@link com.flow.agent.FlowAgent#premain} before any
 * instrumentation is installed.
 */
public class AgentConfigHolder {

    private static volatile String graphId;
    private static volatile String serviceName;

    private AgentConfigHolder() {}

    /** Called once during agent startup. */
    public static void init(AgentConfig config) {
        graphId     = config.getGraphId();
        serviceName = config.getServiceName() != null
                ? config.getServiceName()
                : config.getGraphId();   // fallback: use graphId as service name
    }

    /**
     * The customer's graph/service identifier (set via {@code flow.graphId}).
     * Injected as {@code X-Flow-Service} header on outgoing calls.
     * May be {@code null} if the agent was not yet initialised (safe to pass to injector).
     */
    public static String getGraphId() { return graphId; }

    /**
     * Human-readable service name. Falls back to {@code graphId} when not explicitly set.
     */
    public static String getServiceName() { return serviceName; }
}

