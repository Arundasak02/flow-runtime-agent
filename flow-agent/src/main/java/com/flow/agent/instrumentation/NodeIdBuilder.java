package com.flow.agent.instrumentation;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds nodeId strings from a runtime class and method.
 *
 * <p>Output <strong>MUST</strong> exactly match what {@code flow-java-adapter}'s scanner produces.
 *
 * <p>Format:
 * <pre>{fully.qualified.ClassName}#{methodName}({paramType1}, {paramType2}):{returnType}</pre>
 *
 * <p>Examples (from flow.json):
 * <pre>
 * com.greens.order.core.OrderService#placeOrder(String):String
 * com.greens.order.core.OrderService#validateCart(String):void
 * com.greens.order.config.KafkaProducerConfig#kafkaTemplate():KafkaTemplate&lt;String, String&gt;
 * </pre>
 *
 * <p>The cache ensures the nodeId is computed only once per {@link Method} instance,
 * giving zero allocation cost on hot paths.
 */
public class NodeIdBuilder {

    /** One-time cache: Method → nodeId. Populated lazily on first call per method. */
    private static final int MAX_CACHE_SIZE = 16_384;
    private static final Map<Method, String> CACHE = new ConcurrentHashMap<>(256);

    /**
     * Build (or retrieve from cache) the nodeId for the given declaring class and method.
     *
     * @param declaringClass the class that owns the method (may be a proxy — resolved automatically)
     * @param method         the method being instrumented
     * @return nodeId string, e.g. {@code com.example.OrderService#placeOrder(String):String}
     */
    public static String build(Class<?> declaringClass, Method method) {
        String cached = CACHE.get(method);
        if (cached != null) return cached;
        String nodeId = buildInternal(declaringClass, method);
        if (CACHE.size() < MAX_CACHE_SIZE) {
            CACHE.putIfAbsent(method, nodeId);
        }
        return nodeId;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static String buildInternal(Class<?> declaringClass, Method method) {
        // 1. Resolve proxy to real class
        Class<?> realClass = ProxyResolver.resolve(declaringClass);
        String className = realClass.getName();

        // 2. Method name
        String methodName = method.getName();

        // 3. Parameter types — use generic types to preserve e.g. List<String>
        Type[] genericParamTypes = method.getGenericParameterTypes();
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < genericParamTypes.length; i++) {
            if (i > 0) params.append(", ");
            params.append(simplifyType(genericParamTypes[i].getTypeName()));
        }

        // 4. Return type — use generic type to preserve e.g. KafkaTemplate<String, String>
        String returnType = simplifyType(method.getGenericReturnType().getTypeName());

        // 5. Assemble: com.example.Foo#bar(String, int):void
        return className + "#" + methodName + "(" + params + "):" + returnType;
    }

    /**
     * Simplify Java fully-qualified type names to match flow.json conventions.
     *
     * <ul>
     *   <li>{@code java.lang.String} → {@code String}</li>
     *   <li>{@code java.util.List<java.lang.String>} → {@code List<String>}</li>
     *   <li>All other types remain fully qualified.</li>
     * </ul>
     *
     * <p><strong>WARNING:</strong> These substitution rules MUST exactly match the scanner's
     * {@code SignatureNormalizer}. Any deviation produces an unresolvable nodeId.
     */
    static String simplifyType(String typeName) {
        // Order matters: longer patterns before shorter ones to avoid partial replacement.
        String result = typeName;

        // java.lang.*
        result = result.replace("java.lang.String",    "String");
        result = result.replace("java.lang.Integer",   "Integer");
        result = result.replace("java.lang.Long",      "Long");
        result = result.replace("java.lang.Boolean",   "Boolean");
        result = result.replace("java.lang.Double",    "Double");
        result = result.replace("java.lang.Float",     "Float");
        result = result.replace("java.lang.Object",    "Object");
        result = result.replace("java.lang.Byte",      "Byte");
        result = result.replace("java.lang.Short",     "Short");
        result = result.replace("java.lang.Character", "Character");

        // java.util.*
        result = result.replace("java.util.List",     "List");
        result = result.replace("java.util.Map",      "Map");
        result = result.replace("java.util.Set",      "Set");
        result = result.replace("java.util.Optional", "Optional");

        return result;
    }
}

