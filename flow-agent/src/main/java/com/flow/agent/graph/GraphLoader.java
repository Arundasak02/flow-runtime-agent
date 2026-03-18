package com.flow.agent.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.agent.monitor.AgentLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads static graph JSON from application classpath.
 */
public class GraphLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String classpathLocation;

    public GraphLoader(String classpathLocation) {
        this.classpathLocation = classpathLocation;
    }

    public Optional<LoadedGraph> load() {
        if (classpathLocation == null || classpathLocation.isBlank()) {
            return Optional.empty();
        }

        try (InputStream in = openStream(classpathLocation)) {
            if (in == null) {
                AgentLogger.info("Static graph not found on classpath: " + classpathLocation);
                return Optional.empty();
            }

            byte[] bytes = in.readAllBytes();
            String rawJson = new String(bytes, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(bytes);

            String graphId = readText(root, "graphId");
            String version = readText(root, "version");
            Map<String, Object> metadata = extractMetadata(root.get("metadata"));
            String hash = readMetadataHash(metadata);
            if (hash == null) {
                hash = "sha256:" + sha256(bytes);
                metadata.put("graphHash", hash);
            }

            if (graphId == null || graphId.isBlank()) {
                AgentLogger.warn("Static graph found but graphId is missing: " + classpathLocation);
                return Optional.empty();
            }

            AgentLogger.info("Loaded static graph from classpath: " + classpathLocation + " graphId=" + graphId);
            return Optional.of(new LoadedGraph(rawJson, graphId, version, hash, metadata));
        } catch (Exception e) {
            AgentLogger.warn("Failed to load static graph from classpath (" + classpathLocation + "): " + e.getMessage());
            return Optional.empty();
        }
    }

    private InputStream openStream(String path) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            InputStream in = context.getResourceAsStream(path);
            if (in != null) {
                return in;
            }
        }
        return ClassLoader.getSystemResourceAsStream(path);
    }

    private String readText(JsonNode root, String key) {
        if (root == null || !root.has(key) || root.get(key).isNull()) {
            return null;
        }
        return root.get(key).asText();
    }

    private Map<String, Object> extractMetadata(JsonNode metadataNode) {
        if (metadataNode == null || metadataNode.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(metadataNode, new TypeReference<>() { });
    }

    private String readMetadataHash(Map<String, Object> metadata) {
        Object value = metadata.get("graphHash");
        if (value instanceof String) {
            String hash = (String) value;
            if (!hash.isBlank()) {
                return hash;
            }
        }
        return null;
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
