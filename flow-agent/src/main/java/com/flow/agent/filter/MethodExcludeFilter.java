package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import net.bytebuddy.description.method.MethodDescription;

import java.util.Set;

/**
 * Excludes methods that are not meaningful to trace:
 * <ul>
 *   <li>Known Object methods: {@code toString}, {@code hashCode}, {@code equals}, etc.</li>
 *   <li>Constructors (configurable)</li>
 *   <li>Synthetic methods (configurable)</li>
 *   <li>Getters and setters (configurable)</li>
 * </ul>
 */
public class MethodExcludeFilter {

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "toString", "hashCode", "equals", "clone", "finalize",
            "wait", "notify", "notifyAll", "getClass"
    );

    private final boolean skipGettersSetters;
    private final boolean skipConstructors;
    private final boolean skipSynthetic;
    private final boolean skipPrivateMethods;

    public MethodExcludeFilter(AgentConfig.FilterConfig config) {
        this.skipGettersSetters = config.isSkipGettersSetters();
        this.skipConstructors = config.isSkipConstructors();
        this.skipSynthetic = config.isSkipSynthetic();
        this.skipPrivateMethods = config.isSkipPrivateMethods();
    }

    /**
     * @return {@code true} if the method should be skipped (not instrumented)
     */
    public boolean shouldSkip(MethodDescription method) {
        String name = method.getName();

        if (EXCLUDED_NAMES.contains(name)) return true;
        if (skipConstructors && method.isConstructor()) return true;
        if (skipPrivateMethods && method.isPrivate()) return true;
        if (skipSynthetic && method.isSynthetic()) return true;
        if (skipGettersSetters && isGetterOrSetter(name)) return true;

        return false;
    }

    private boolean isGetterOrSetter(String name) {
        return (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))
                && name.length() > 3;
    }
}

