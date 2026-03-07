package com.flow.agent.sampling;

/**
 * Sampling decision interface.
 * Implementations decide whether a given trace should be observed.
 */
public interface Sampler {

    /**
     * @param traceId the trace ID for the current request
     * @return {@code true} if this trace should be recorded
     */
    boolean sample(String traceId);
}

