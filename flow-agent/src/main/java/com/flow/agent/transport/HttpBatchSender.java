package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.RuntimeEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Sends batched events to Flow Core Service via asynchronous HTTP POST.
 *
 * <p>Uses Java 11's {@link HttpClient#sendAsync} — the application thread is NEVER blocked.
 * The circuit breaker protects against repeated FCS outages.
 *
 * <p>Endpoint: {@code POST {flow.server.url}/ingest/runtime}
 * Headers: {@code Content-Type: application/json}, {@code Content-Encoding: gzip}
 */
public class HttpBatchSender {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final CircuitBreaker circuitBreaker;
    private final PayloadSerializer serializer;

    public HttpBatchSender(AgentConfig.ServerConfig serverConfig, CircuitBreaker circuitBreaker) {
        this.baseUrl = serverConfig.getUrl();
        this.apiKey = serverConfig.getApiKey();
        this.circuitBreaker = circuitBreaker;
        this.serializer = new PayloadSerializer();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(serverConfig.getConnectTimeoutMs()))
                .build();
    }

    /**
     * Send a batch of events to FCS. Always non-blocking (uses {@code sendAsync}).
     * If the circuit breaker is OPEN, events are dropped silently.
     *
     * @param graphId the customer's graph identifier
     * @param events  the events to ship; must not be empty
     */
    public void send(String graphId, List<RuntimeEvent> events) {
        if (events == null || events.isEmpty()) return;

        if (!circuitBreaker.allowRequest()) {
            AgentMetrics.incrementEventsDropped(events.size());
            return; // circuit is OPEN — drop batch
        }

        try {
            byte[] body = serializer.serialize(graphId, events);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/ingest/runtime"))
                    .timeout(Duration.ofMillis(5000))
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            circuitBreaker.recordSuccess();
                            AgentMetrics.incrementBatchesSent();
                        } else {
                            circuitBreaker.recordFailure();
                            AgentMetrics.incrementBatchesFailed();
                            System.err.println("[flow-agent] FCS returned HTTP "
                                    + response.statusCode() + " for batch of " + events.size());
                        }
                    })
                    .exceptionally(ex -> {
                        circuitBreaker.recordFailure();
                        AgentMetrics.incrementBatchesFailed();
                        System.err.println("[flow-agent] Failed to send batch: " + ex.getMessage());
                        return null;
                    });

        } catch (Throwable t) {
            // Serialization or request-construction failure
            circuitBreaker.recordFailure();
            AgentMetrics.incrementBatchesFailed();
            System.err.println("[flow-agent] Failed to build HTTP request: " + t.getMessage());
        }
    }
}

