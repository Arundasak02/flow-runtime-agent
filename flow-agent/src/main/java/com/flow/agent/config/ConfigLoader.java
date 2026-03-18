package com.flow.agent.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads configuration from system properties, env vars, properties file, and defaults.
 * Priority (highest to lowest): system props > env vars > config file > defaults.
 */
public class ConfigLoader {

    public static AgentConfig load(String agentArgs) {
        AgentConfig config = new AgentConfig();

        // 1. Load defaults (already set in AgentConfig field initializers)

        // 2. Load config file if present
        //    -Dflow.config=/path/to/flow-agent.properties
        //    OR flow-agent.properties next to agent JAR
        String configPath = System.getProperty("flow.config");
        if (configPath != null) {
            loadPropertiesFile(config, configPath);
        } else {
            // Try default locations
            tryLoadDefaultConfigFile(config);
        }

        // 3. Override with environment variables
        applyEnvironmentVariables(config);

        // 4. Override with system properties (highest priority)
        applySystemProperties(config);

        // 5. Parse agentArgs if provided (key=value,key=value format)
        if (agentArgs != null && !agentArgs.isEmpty()) {
            applyAgentArgs(config, agentArgs);
        }

        // 6. Validate required fields
        validate(config);

        return config;
    }

    // ── Environment variable mapping ─────────────────────────────────────────

    private static void applyEnvironmentVariables(AgentConfig config) {
        String serverUrl = System.getenv("FLOW_SERVER_URL");
        if (serverUrl != null && !serverUrl.isEmpty()) {
            config.getServer().setUrl(serverUrl);
        }

        String apiKey = System.getenv("FLOW_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            config.getServer().setApiKey(apiKey);
        }

        String graphId = System.getenv("FLOW_GRAPH_ID");
        if (graphId != null && !graphId.isEmpty()) {
            config.setGraphId(graphId);
        }

        String serviceName = System.getenv("FLOW_SERVICE_NAME");
        if (serviceName != null && !serviceName.isEmpty()) {
            config.setServiceName(serviceName);
        }

        String packagesInclude = System.getenv("FLOW_PACKAGES_INCLUDE");
        if (packagesInclude != null && !packagesInclude.isEmpty()) {
            config.getPackages().setInclude(splitCsv(packagesInclude));
        }

        String packagesExclude = System.getenv("FLOW_PACKAGES_EXCLUDE");
        if (packagesExclude != null && !packagesExclude.isEmpty()) {
            config.getPackages().setExclude(splitCsv(packagesExclude));
        }

        String enabled = System.getenv("FLOW_ENABLED");
        if (enabled != null && !enabled.isEmpty()) {
            config.setEnabled(Boolean.parseBoolean(enabled));
        }

        String graphAutoPublish = System.getenv("FLOW_GRAPH_AUTO_PUBLISH");
        if (graphAutoPublish != null && !graphAutoPublish.isEmpty()) {
            config.getGraph().setAutoPublish(Boolean.parseBoolean(graphAutoPublish));
        }

        String graphClasspath = System.getenv("FLOW_GRAPH_CLASSPATH");
        if (graphClasspath != null && !graphClasspath.isEmpty()) {
            config.getGraph().setClasspath(graphClasspath);
        }

        String graphDedup = System.getenv("FLOW_GRAPH_DEDUP");
        if (graphDedup != null && !graphDedup.isEmpty()) {
            config.getGraph().setDedup(Boolean.parseBoolean(graphDedup));
        }
    }

    // ── System property mapping ───────────────────────────────────────────────

    private static void applySystemProperties(AgentConfig config) {
        String serverUrl = System.getProperty("flow.server.url");
        if (serverUrl != null && !serverUrl.isEmpty()) {
            config.getServer().setUrl(serverUrl);
        }

        String apiKey = System.getProperty("flow.server.api-key");
        if (apiKey != null && !apiKey.isEmpty()) {
            config.getServer().setApiKey(apiKey);
        }

        String connectTimeout = System.getProperty("flow.server.connect-timeout-ms");
        if (connectTimeout != null) {
            config.getServer().setConnectTimeoutMs(Integer.parseInt(connectTimeout));
        }

        String readTimeout = System.getProperty("flow.server.read-timeout-ms");
        if (readTimeout != null) {
            config.getServer().setReadTimeoutMs(Integer.parseInt(readTimeout));
        }

        String graphId = System.getProperty("flow.graph-id");
        if (graphId != null && !graphId.isEmpty()) {
            config.setGraphId(graphId);
        }

        String serviceName = System.getProperty("flow.service-name");
        if (serviceName != null && !serviceName.isEmpty()) {
            config.setServiceName(serviceName);
        }

        String packagesInclude = System.getProperty("flow.packages.include");
        if (packagesInclude != null && !packagesInclude.isEmpty()) {
            config.getPackages().setInclude(splitCsv(packagesInclude));
        }

        String packagesExclude = System.getProperty("flow.packages.exclude");
        if (packagesExclude != null && !packagesExclude.isEmpty()) {
            config.getPackages().setExclude(splitCsv(packagesExclude));
        }

        String enabled = System.getProperty("flow.enabled");
        if (enabled != null && !enabled.isEmpty()) {
            config.setEnabled(Boolean.parseBoolean(enabled));
        }

        String bufferSize = System.getProperty("flow.pipeline.buffer-size");
        if (bufferSize != null) {
            config.getPipeline().setBufferSize(Integer.parseInt(bufferSize));
        }

        String batchSize = System.getProperty("flow.pipeline.batch-size");
        if (batchSize != null) {
            config.getPipeline().setBatchSize(Integer.parseInt(batchSize));
        }

        String flushInterval = System.getProperty("flow.pipeline.flush-interval-ms");
        if (flushInterval != null) {
            config.getPipeline().setFlushIntervalMs(Integer.parseInt(flushInterval));
        }

        String failureThreshold = System.getProperty("flow.circuit-breaker.failure-threshold");
        if (failureThreshold != null) {
            config.getCircuitBreaker().setFailureThreshold(Integer.parseInt(failureThreshold));
        }

        String resetTimeout = System.getProperty("flow.circuit-breaker.reset-timeout-ms");
        if (resetTimeout != null) {
            config.getCircuitBreaker().setResetTimeoutMs(Integer.parseInt(resetTimeout));
        }

        String skipGettersSetters = System.getProperty("flow.filter.skip-getters-setters");
        if (skipGettersSetters != null) {
            config.getFilter().setSkipGettersSetters(Boolean.parseBoolean(skipGettersSetters));
        }

        String skipConstructors = System.getProperty("flow.filter.skip-constructors");
        if (skipConstructors != null) {
            config.getFilter().setSkipConstructors(Boolean.parseBoolean(skipConstructors));
        }

        String skipSynthetic = System.getProperty("flow.filter.skip-synthetic");
        if (skipSynthetic != null) {
            config.getFilter().setSkipSynthetic(Boolean.parseBoolean(skipSynthetic));
        }

        String graphAutoPublish = System.getProperty("flow.graph.auto-publish");
        if (graphAutoPublish != null) {
            config.getGraph().setAutoPublish(Boolean.parseBoolean(graphAutoPublish));
        }

        String graphClasspath = System.getProperty("flow.graph.classpath");
        if (graphClasspath != null && !graphClasspath.isEmpty()) {
            config.getGraph().setClasspath(graphClasspath);
        }

        String graphFilePath = System.getProperty("flow.graph.file-path");
        if (graphFilePath != null && !graphFilePath.isEmpty()) {
            config.getGraph().setFilePath(graphFilePath);
        }

        String graphDedup = System.getProperty("flow.graph.dedup");
        if (graphDedup != null) {
            config.getGraph().setDedup(Boolean.parseBoolean(graphDedup));
        }

        // ── Capture config ──────────────────────────────────────────────────
        String captureEnabled = System.getProperty("flow.capture.enabled");
        if (captureEnabled != null) {
            config.getCapture().setEnabled(Boolean.parseBoolean(captureEnabled));
        }

        String captureMaxDepth = System.getProperty("flow.capture.max-depth");
        if (captureMaxDepth != null) {
            config.getCapture().setMaxDepth(Integer.parseInt(captureMaxDepth));
        }

        String captureMaxFields = System.getProperty("flow.capture.max-fields");
        if (captureMaxFields != null) {
            config.getCapture().setMaxFields(Integer.parseInt(captureMaxFields));
        }

        String captureExcludePatterns = System.getProperty("flow.capture.global-exclude-patterns");
        if (captureExcludePatterns != null && !captureExcludePatterns.isEmpty()) {
            config.getCapture().setGlobalExcludePatterns(splitCsv(captureExcludePatterns));
        }
    }

    // ── Agent args parsing (key=value,key2=value2) ───────────────────────────

    private static void applyAgentArgs(AgentConfig config, String agentArgs) {
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                switch (key) {
                    case "server.url": config.getServer().setUrl(value); break;
                    case "graph-id": config.setGraphId(value); break;
                    case "packages.include": config.getPackages().setInclude(splitCsv(value)); break;
                    case "enabled": config.setEnabled(Boolean.parseBoolean(value)); break;
                    default: break; // unknown key ignored
                }
            }
        }
    }

    // ── Properties file loading ───────────────────────────────────────────────

    private static void loadPropertiesFile(AgentConfig config, String path) {
        try {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(Paths.get(path))) {
                props.load(is);
            }
            applyProperties(config, props);
        } catch (IOException e) {
            System.err.println("[flow-agent] Could not load config file: " + path + " — " + e.getMessage());
        }
    }

    private static void tryLoadDefaultConfigFile(AgentConfig config) {
        // Try classpath default
        try (InputStream is = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("flow-agent.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                applyProperties(config, props);
            }
        } catch (IOException e) {
            // Silently ignore — defaults will be used
        }
    }

    private static void applyProperties(AgentConfig config, Properties props) {
        applyIfPresent(props, "flow.server.url", v -> config.getServer().setUrl(v));
        applyIfPresent(props, "flow.server.api-key", v -> config.getServer().setApiKey(v));
        applyIfPresent(props, "flow.server.connect-timeout-ms",
                v -> config.getServer().setConnectTimeoutMs(Integer.parseInt(v)));
        applyIfPresent(props, "flow.server.read-timeout-ms",
                v -> config.getServer().setReadTimeoutMs(Integer.parseInt(v)));
        applyIfPresent(props, "flow.graph-id", v -> config.setGraphId(v));
        applyIfPresent(props, "flow.service-name", v -> config.setServiceName(v));
        applyIfPresent(props, "flow.packages.include",
                v -> config.getPackages().setInclude(splitCsv(v)));
        applyIfPresent(props, "flow.packages.exclude",
                v -> config.getPackages().setExclude(splitCsv(v)));
        applyIfPresent(props, "flow.enabled", v -> config.setEnabled(Boolean.parseBoolean(v)));
        applyIfPresent(props, "flow.pipeline.buffer-size",
                v -> config.getPipeline().setBufferSize(Integer.parseInt(v)));
        applyIfPresent(props, "flow.pipeline.batch-size",
                v -> config.getPipeline().setBatchSize(Integer.parseInt(v)));
        applyIfPresent(props, "flow.pipeline.flush-interval-ms",
                v -> config.getPipeline().setFlushIntervalMs(Integer.parseInt(v)));
        applyIfPresent(props, "flow.circuit-breaker.failure-threshold",
                v -> config.getCircuitBreaker().setFailureThreshold(Integer.parseInt(v)));
        applyIfPresent(props, "flow.circuit-breaker.reset-timeout-ms",
                v -> config.getCircuitBreaker().setResetTimeoutMs(Integer.parseInt(v)));

        // ── Capture config ──────────────────────────────────────────────────
        applyIfPresent(props, "flow.capture.enabled",
                v -> config.getCapture().setEnabled(Boolean.parseBoolean(v)));
        applyIfPresent(props, "flow.capture.max-depth",
                v -> config.getCapture().setMaxDepth(Integer.parseInt(v)));
        applyIfPresent(props, "flow.capture.max-fields",
                v -> config.getCapture().setMaxFields(Integer.parseInt(v)));
        applyIfPresent(props, "flow.capture.global-exclude-patterns",
                v -> config.getCapture().setGlobalExcludePatterns(splitCsv(v)));

        applyIfPresent(props, "flow.graph.auto-publish",
                v -> config.getGraph().setAutoPublish(Boolean.parseBoolean(v)));
        applyIfPresent(props, "flow.graph.classpath",
                v -> config.getGraph().setClasspath(v));
        applyIfPresent(props, "flow.graph.file-path",
                v -> config.getGraph().setFilePath(v));
        applyIfPresent(props, "flow.graph.dedup",
                v -> config.getGraph().setDedup(Boolean.parseBoolean(v)));
    }

    private static void applyIfPresent(Properties props, String key,
                                        java.util.function.Consumer<String> setter) {
        String val = props.getProperty(key);
        if (val != null && !val.isEmpty()) {
            setter.accept(val.trim());
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validate(AgentConfig config) {
        if (config.getServer() == null || config.getServer().getUrl() == null
                || config.getServer().getUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "[flow-agent] flow.server.url is required. Use -Dflow.server.url=http://...");
        }
        if (config.getGraphId() == null || config.getGraphId().isEmpty()) {
            throw new IllegalArgumentException(
                    "[flow-agent] flow.graph-id is required. Use -Dflow.graph-id=my-service");
        }
        if (config.getPackages() == null
                || config.getPackages().getInclude() == null
                || config.getPackages().getInclude().isEmpty()) {
            throw new IllegalArgumentException(
                    "[flow-agent] flow.packages.include is required. "
                  + "Use -Dflow.packages.include=com.mycompany");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        for (String s : value.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}

