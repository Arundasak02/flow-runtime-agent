package com.flow.agent.instrumentation;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Resolves Spring CGLIB proxies and JDK dynamic proxies to their real underlying class.
 *
 * <p>Spring wraps beans in proxies:
 * <ul>
 *   <li>CGLIB: {@code OrderService$$SpringCGLIB$$0} — superclass is the real class</li>
 *   <li>JDK:   {@code $Proxy123}                   — inspect interfaces for customer-package match</li>
 * </ul>
 */
public class ProxyResolver {

    /** Initialized at startup from {@code AgentConfig.packages.include}. */
    private static volatile List<String> packagePrefixes = List.of();

    public static void init(List<String> packages) {
        packagePrefixes = packages;
    }

    /**
     * Resolve a potentially-proxy class to the real customer class.
     *
     * @param clazz the class as seen by the JVM (may be a proxy)
     * @return the real, non-proxy class to use for nodeId building
     */
    public static Class<?> resolve(Class<?> clazz) {
        String name = clazz.getName();

        // ── CGLIB proxies: OrderService$$SpringCGLIB$$0 ──────────────────────
        if (name.contains("$$")) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return superClass;
            }
        }

        // ── Lambdas: OrderService$$Lambda$42 — skip (not in static graph) ────
        if (name.contains("$$Lambda$")) {
            return clazz; // caller will filter by package
        }

        // ── JDK dynamic proxies: $Proxy123 ───────────────────────────────────
        if (Proxy.isProxyClass(clazz) || name.startsWith("com.sun.proxy.$Proxy")) {
            List<String> prefixes = packagePrefixes;
            for (Class<?> iface : clazz.getInterfaces()) {
                for (String prefix : prefixes) {
                    if (iface.getName().startsWith(prefix)) {
                        return iface;
                    }
                }
            }
        }

        return clazz;
    }
}

