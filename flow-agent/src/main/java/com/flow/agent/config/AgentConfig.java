package com.flow.agent.config;

import java.util.List;

/**
 * Immutable agent configuration. Built by ConfigLoader.
 */
public class AgentConfig {

    private boolean enabled = true;
    private ServerConfig server = new ServerConfig();
    private String graphId;
    private String serviceName;
    private PackagesConfig packages = new PackagesConfig();
    private FilterConfig filter = new FilterConfig();
    private SamplingConfig sampling = new SamplingConfig();
    private PipelineConfig pipeline = new PipelineConfig();
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    private CaptureConfig capture = new CaptureConfig();
    private GraphConfig graph = new GraphConfig();

    // ── Nested config classes ────────────────────────────────────────────────

    public static class ServerConfig {
        private String url;
        private String apiKey;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 5000;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public static class PackagesConfig {
        private List<String> include;
        private List<String> exclude = List.of();

        public List<String> getInclude() { return include; }
        public void setInclude(List<String> include) { this.include = include; }
        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
    }

    public static class FilterConfig {
        private boolean skipGettersSetters = true;
        private boolean skipConstructors = true;
        private boolean skipPrivateMethods = false;
        private boolean skipSynthetic = true;

        public boolean isSkipGettersSetters() { return skipGettersSetters; }
        public void setSkipGettersSetters(boolean skipGettersSetters) { this.skipGettersSetters = skipGettersSetters; }
        public boolean isSkipConstructors() { return skipConstructors; }
        public void setSkipConstructors(boolean skipConstructors) { this.skipConstructors = skipConstructors; }
        public boolean isSkipPrivateMethods() { return skipPrivateMethods; }
        public void setSkipPrivateMethods(boolean skipPrivateMethods) { this.skipPrivateMethods = skipPrivateMethods; }
        public boolean isSkipSynthetic() { return skipSynthetic; }
        public void setSkipSynthetic(boolean skipSynthetic) { this.skipSynthetic = skipSynthetic; }
    }

    public static class SamplingConfig {
        private double rate = 1.0;

        public double getRate() { return rate; }
        public void setRate(double rate) { this.rate = rate; }
    }

    public static class PipelineConfig {
        private int bufferSize = 8192;
        private int batchSize = 100;
        private int flushIntervalMs = 200;

        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(int flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    }

    public static class CircuitBreakerConfig {
        private int failureThreshold = 3;
        private int resetTimeoutMs = 30000;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getResetTimeoutMs() { return resetTimeoutMs; }
        public void setResetTimeoutMs(int resetTimeoutMs) { this.resetTimeoutMs = resetTimeoutMs; }
    }

    public static class GraphConfig {
        private boolean autoPublish = true;
        private String classpath = "META-INF/flow/flow.json";
        /**
         * Optional filesystem path to {@code flow.json}.
         * <p>
         * This is useful for production/SaaS deployments where the graph snapshot is mounted
         * into the container at runtime (instead of being packaged inside the application JAR).
         */
        private String filePath;
        private boolean dedup = true;

        public boolean isAutoPublish() { return autoPublish; }
        public void setAutoPublish(boolean autoPublish) { this.autoPublish = autoPublish; }
        public String getClasspath() { return classpath; }
        public void setClasspath(String classpath) { this.classpath = classpath; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public boolean isDedup() { return dedup; }
        public void setDedup(boolean dedup) { this.dedup = dedup; }
    }

    /**
     * Configuration for checkpoint object extraction and PII safety.
     *
     * <p>Global exclude patterns act as a <strong>safety net</strong> — even if a developer
     * forgets {@code @FlowExclude} on a PII field, field names matching these patterns are
     * never captured. Patterns are case-insensitive and support leading/trailing wildcards.
     */
    public static class CaptureConfig {
        private boolean enabled = true;
        private int maxDepth = 2;
        private int maxFields = 50;
        private List<String> globalExcludePatterns = List.of(
                "*password*", "*passwd*", "*secret*", "*token*",
                "*creditcard*", "*credit_card*", "*cardnumber*", "*card_number*",
                "*ssn*", "*socialsecurity*", "*social_security*",
                "*email*", "*phone*", "*mobile*",
                "*address*",
                "*apikey*", "*api_key*",
                "*privatekey*", "*private_key*",
                "*accesstoken*", "*access_token*", "*refreshtoken*", "*refresh_token*",
                "*authorization*", "*auth_header*",
                "*cvv*", "*cvc*", "*expiry*",
                "*bankaccount*", "*bank_account*", "*routing*", "*iban*",
                "*pin*", "*otp*"
        );

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getMaxFields() { return maxFields; }
        public void setMaxFields(int maxFields) { this.maxFields = maxFields; }
        public List<String> getGlobalExcludePatterns() { return globalExcludePatterns; }
        public void setGlobalExcludePatterns(List<String> globalExcludePatterns) {
            this.globalExcludePatterns = globalExcludePatterns;
        }
    }

    // ── Top-level getters/setters ────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }

    public String getGraphId() { return graphId; }
    public void setGraphId(String graphId) { this.graphId = graphId; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public PackagesConfig getPackages() { return packages; }
    public void setPackages(PackagesConfig packages) { this.packages = packages; }

    public FilterConfig getFilter() { return filter; }
    public void setFilter(FilterConfig filter) { this.filter = filter; }

    public SamplingConfig getSampling() { return sampling; }
    public void setSampling(SamplingConfig sampling) { this.sampling = sampling; }

    public PipelineConfig getPipeline() { return pipeline; }
    public void setPipeline(PipelineConfig pipeline) { this.pipeline = pipeline; }

    public CircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public CaptureConfig getCapture() { return capture; }
    public void setCapture(CaptureConfig capture) { this.capture = capture; }

    public GraphConfig getGraph() { return graph; }
    public void setGraph(GraphConfig graph) { this.graph = graph; }
}

