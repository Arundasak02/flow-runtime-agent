package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

/**
 * Orchestrates all filters to determine whether a class or method should be instrumented.
 *
 * <p>All filtering decisions happen at <strong>class-load time</strong> (inside ByteBuddy's
 * type/method matchers) — there is zero per-call overhead at runtime.
 */
public class FilterChain {

    private final PackageFilter packageFilter;
    private final MethodExcludeFilter methodExcludeFilter;
    private final BridgeMethodFilter bridgeMethodFilter;

    private FilterChain(PackageFilter packageFilter,
                        MethodExcludeFilter methodExcludeFilter,
                        BridgeMethodFilter bridgeMethodFilter) {
        this.packageFilter = packageFilter;
        this.methodExcludeFilter = methodExcludeFilter;
        this.bridgeMethodFilter = bridgeMethodFilter;
    }

    public static FilterChain from(AgentConfig config) {
        return new FilterChain(
                new PackageFilter(config.getPackages()),
                new MethodExcludeFilter(config.getFilter()),
                new BridgeMethodFilter()
        );
    }

    /**
     * Should this class be instrumented at all?
     *
     * @param className fully-qualified class name
     */
    public boolean shouldInstrumentClass(String className) {
        return packageFilter.matches(className);
    }

    /**
     * Should this specific method within an already-matched class be instrumented?
     */
    public boolean shouldInstrumentMethod(TypeDescription type, MethodDescription method) {
        if (bridgeMethodFilter.shouldSkip(method)) return false;
        if (methodExcludeFilter.shouldSkip(method)) return false;
        return true;
    }
}

