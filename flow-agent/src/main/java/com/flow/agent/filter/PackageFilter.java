package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;

import java.util.List;

/**
 * Filters classes based on configured include/exclude package prefixes.
 *
 * <p>A class is accepted if:
 * <ol>
 *   <li>Its fully-qualified name starts with at least one include prefix, AND</li>
 *   <li>Its fully-qualified name does NOT start with any exclude prefix.</li>
 * </ol>
 */
public class PackageFilter {

    private final List<String> includePrefixes;
    private final List<String> excludePrefixes;

    public PackageFilter(AgentConfig.PackagesConfig config) {
        this.includePrefixes = config.getInclude();
        this.excludePrefixes = config.getExclude() != null ? config.getExclude() : List.of();
    }

    /**
     * @param className fully-qualified class name
     * @return {@code true} if the class should be instrumented
     */
    public boolean matches(String className) {
        // Must match at least one include prefix
        boolean included = false;
        for (String prefix : includePrefixes) {
            if (className.startsWith(prefix)) {
                included = true;
                break;
            }
        }
        if (!included) return false;

        // Must NOT match any exclude prefix
        for (String prefix : excludePrefixes) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }

        return true;
    }
}

