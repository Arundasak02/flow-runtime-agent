package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.pipeline.RuntimeEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HttpBatchSender} — async HTTP POST to FCS.
 * Uses JDK's built-in {@link HttpServer} as a mock FCS endpoint.
 */
class HttpBatchSenderTest {

    private HttpServer mockServer;
    private int port;
    private final CopyOnWriteArrayList<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private CountDownLatch requestLatch;

    static class ReceivedRequest {
        final String path;
        final String contentType;
        final String contentEncoding;
        final String authorization;
        final String body;

        ReceivedRequest(String path, String contentType, String contentEncoding,
                        String authorization, String body) {
            this.path = path;
            this.contentType = contentType;
            this.contentEncoding = contentEncoding;
            this.authorization = authorization;
            this.body = body;
        }
    }

    @BeforeEach
    void startMockServer() throws IOException {
        requestLatch = new CountDownLatch(1);
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockServer.getAddress().getPort();

        mockServer.createContext("/ingest/runtime", exchange -> {
            try {
                String body = readBody(exchange);
                receivedRequests.add(new ReceivedRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("Content-Encoding"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        body
                ));
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
                requestLatch.countDown();
            }
        });

        mockServer.start();
    }

    @AfterEach
    void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    private AgentConfig.ServerConfig serverConfig(String apiKey) {
        AgentConfig.ServerConfig config = new AgentConfig.ServerConfig();
        config.setUrl("http://localhost:" + port);
        config.setApiKey(apiKey);
        config.setConnectTimeoutMs(2000);
        config.setReadTimeoutMs(2000);
        return config;
    }

    private RuntimeEvent makeEvent() {
        return new RuntimeEvent(
                "trace-1", "span-1", null,
                "com.example.Service#doWork(String):void",
                "METHOD_ENTER", System.currentTimeMillis(), 0L, null);
    }

    @Test
    void send_postsToIngestRuntimeEndpoint() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("test-graph", List.of(makeEvent()));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS), "Request should arrive");
        assertEquals(1, receivedRequests.size());
        assertEquals("/ingest/runtime", receivedRequests.get(0).path);
    }

    @Test
    void send_setsCorrectContentHeaders() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("test-graph", List.of(makeEvent()));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        ReceivedRequest req = receivedRequests.get(0);
        assertEquals("application/json", req.contentType);
        assertEquals("gzip", req.contentEncoding);
    }

    @Test
    void send_includesAuthorizationHeader_whenApiKeySet() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig("my-secret-key"), cb);

        sender.send("test-graph", List.of(makeEvent()));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertEquals("Bearer my-secret-key", receivedRequests.get(0).authorization);
    }

    @Test
    void send_omitsAuthorizationHeader_whenApiKeyNull() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("test-graph", List.of(makeEvent()));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertNull(receivedRequests.get(0).authorization);
    }

    @Test
    void send_payloadContainsGraphIdAndEvents() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("order-service", List.of(makeEvent()));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        String json = receivedRequests.get(0).body;
        assertTrue(json.contains("order-service"), "Payload must contain graphId");
        assertTrue(json.contains("com.example.Service#doWork"), "Payload must contain nodeId");
    }

    @Test
    void send_emptyList_doesNothing() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("test-graph", List.of());

        // Should NOT make any HTTP request
        assertFalse(requestLatch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(receivedRequests.isEmpty());
    }

    @Test
    void send_circuitBreakerOpen_dropsEvents() throws Exception {
        AgentConfig.CircuitBreakerConfig cbConfig = new AgentConfig.CircuitBreakerConfig();
        cbConfig.setFailureThreshold(1);
        CircuitBreaker cb = new CircuitBreaker(cbConfig);
        cb.recordFailure(); // force OPEN

        HttpBatchSender sender = new HttpBatchSender(serverConfig(null), cb);

        sender.send("test-graph", List.of(makeEvent()));

        // Should NOT make HTTP request — circuit is OPEN
        assertFalse(requestLatch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(receivedRequests.isEmpty());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private String readBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        // Decompress GZIP
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(gzip.readAllBytes());
        }
    }
}

