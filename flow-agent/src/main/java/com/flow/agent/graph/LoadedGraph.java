package com.flow.agent.graph;

import java.util.Map;

/**
 * Immutable representation of a static graph loaded from classpath.
 */
public class LoadedGraph {

    private final String rawJson;
    private final String graphId;
    private final String version;
    private final String graphHash;
    private final Map<String, Object> metadata;

    public LoadedGraph(String rawJson, String graphId, String version, String graphHash, Map<String, Object> metadata) {
        this.rawJson = rawJson;
        this.graphId = graphId;
        this.version = version;
        this.graphHash = graphHash;
        this.metadata = metadata;
    }

    public String rawJson() {
        return rawJson;
    }

    public String graphId() {
        return graphId;
    }

    public String version() {
        return version;
    }

    public String graphHash() {
        return graphHash;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
