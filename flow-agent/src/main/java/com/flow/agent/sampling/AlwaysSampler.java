package com.flow.agent.sampling;

/**
 * Always-on sampler — records 100% of traces.
 * Phase 1 default. {@code AdaptiveSampler} deferred to Phase 5.
 */
public class AlwaysSampler implements Sampler {

    @Override
    public boolean sample(String traceId) {
        return true;
    }
}

