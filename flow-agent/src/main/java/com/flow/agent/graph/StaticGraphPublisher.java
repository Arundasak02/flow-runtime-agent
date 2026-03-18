package com.flow.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.agent.config.AgentConfig;
import com.flow.agent.monitor.AgentLogger;
import com.flow.agent.transport.CircuitBreaker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Publishes static graph snapshots to Flow Core Service asynchronously.
 */
public class StaticGraphPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile String lastPublishedHash;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final CircuitBreaker circuitBreaker;
    private final boolean dedupEnabled;
    private final ExecutorService executor;

    public StaticGraphPublisher(AgentConfig.ServerConfig serverConfig,
                                CircuitBreaker circuitBreaker,
                                boolean dedupEnabled) {
        this.baseUrl = trimTrailingSlash(serverConfig.getUrl());
        this.apiKey = serverConfig.getApiKey();
        this.circuitBreaker = circuitBreaker;
        this.dedupEnabled = dedupEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(serverConfig.getConnectTimeoutMs()))
                .build();
        this.executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory("flow-static-graph-publisher"));
    }

    public void publishAsync(LoadedGraph graph) {
        if (graph == null) {
            return;
        }
        if (dedupEnabled && graph.graphHash() != null && graph.graphHash().equals(lastPublishedHash)) {
            AgentLogger.info("Static graph publish skipped (hash unchanged): " + graph.graphHash());
            return;
        }
        executor.execute(() -> publishWithRetry(graph));
    }

    private void publishWithRetry(LoadedGraph graph) {
        if (!circuitBreaker.allowRequest()) {
            AgentLogger.warn("Static graph publish skipped (circuit breaker OPEN)");
            return;
        }

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(buildRequest(graph));
        } catch (Exception e) {
            AgentLogger.warn("Static graph publish skipped (serialization failed): " + e.getMessage());
            return;
        }

        int maxAttempts = 3;
        long backoffMs = 500;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/ingest/static"))
                        .timeout(Duration.ofMillis(5000))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    circuitBreaker.recordSuccess();
                    lastPublishedHash = graph.graphHash();
                    AgentLogger.info("Static graph published to FCS: graphId=" + graph.graphId()
                            + " hash=" + graph.graphHash()
                            + " status=" + response.statusCode());
                    return;
                }

                circuitBreaker.recordFailure();
                AgentLogger.warn("Static graph publish failed with HTTP " + response.statusCode()
                        + " (attempt " + attempt + "/" + maxAttempts + ")");
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                AgentLogger.warn("Static graph publish error (attempt " + attempt + "/" + maxAttempts + "): "
                        + e.getMessage());
            }

            if (attempt < maxAttempts) {
                sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private Map<String, Object> buildRequest(LoadedGraph graph) throws Exception {
        JsonNode root = MAPPER.readTree(graph.rawJson());

        List<Map<String, Object>> nodes = new ArrayList<>();
        JsonNode nodeArray = root.path("nodes");
        if (nodeArray.isArray()) {
            for (JsonNode node : nodeArray) {
                Map<String, Object> converted = new LinkedHashMap<>();
                converted.put("nodeId", asText(node, "id"));
                converted.put("type", asText(node, "type"));
                converted.put("name", asText(node, "name"));
                JsonNode data = node.get("data");
                if (data != null && !data.isNull()) {
                    converted.put("attributes", MAPPER.convertValue(data, Map.class));
                }
                nodes.add(converted);
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        JsonNode edgeArray = root.path("edges");
        if (edgeArray.isArray()) {
            for (JsonNode edge : edgeArray) {
                Map<String, Object> converted = new LinkedHashMap<>();
                converted.put("edgeId", asText(edge, "id"));
                converted.put("sourceNodeId", asText(edge, "from"));
                converted.put("targetNodeId", asText(edge, "to"));
                converted.put("type", asText(edge, "type"));
                edges.add(converted);
            }
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("graphId", graph.graphId());
        request.put("version", graph.version());
        request.put("graphHash", graph.graphHash());
        request.put("nodes", nodes);
        request.put("edges", edges);
        request.put("metadata", graph.metadata());
        return request;
    }

    private String asText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;

        private DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    }
}
