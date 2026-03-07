package com.flow.agent.instrumentation.extract;

import com.flow.agent.config.AgentConfig.CaptureConfig;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts fields from objects for checkpoint capture, with multi-layered PII protection.
 *
 * <h3>Protection layers (evaluated in order — first match wins):</h3>
 * <ol>
 *   <li><strong>{@code @FlowExclude} on class</strong> — entire type is opaque, returns empty map</li>
 *   <li><strong>{@code @FlowExclude} on field</strong> — field is always skipped</li>
 *   <li><strong>{@code @FlowInclude} on class</strong> — opt-in mode: only {@code @FlowInclude} fields</li>
 *   <li><strong>Global exclude patterns</strong> (config) — safety net: field names matching
 *       patterns like {@code *password*}, {@code *email*} are always skipped</li>
 *   <li><strong>{@code FlowCapture} call-site overrides</strong> — further restrict per call
 *       (can never override {@code @FlowExclude})</li>
 *   <li><strong>Depth limit</strong> — stops recursion at configured max depth</li>
 *   <li><strong>Field count limit</strong> — stops after configured max fields</li>
 *   <li><strong>Cycle detection</strong> — identity-based visited set prevents infinite loops</li>
 * </ol>
 *
 * <p>Thread-safe: stateless per-call (visited set created fresh each invocation).
 * Annotation lookups cached in a concurrent map for performance.
 */
public class ObjectExtractor {

    // Annotation class names — resolved by name to avoid compile dependency on flow-sdk
    private static final String FLOW_EXCLUDE = "com.flow.sdk.FlowExclude";
    private static final String FLOW_INCLUDE = "com.flow.sdk.FlowInclude";

    // Cache annotation presence per class/field to avoid repeated reflection
    private static final ConcurrentHashMap<Class<?>, Boolean> classExcludeCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> classIncludeCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Field, Boolean> fieldExcludeCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Field, Boolean> fieldIncludeCache = new ConcurrentHashMap<>();

    private final CaptureConfig config;

    public ObjectExtractor(CaptureConfig config) {
        this.config = config;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Extract fields from an object into a flat map, respecting all PII protection layers.
     *
     * @param key     the checkpoint key (used as prefix for nested fields)
     * @param value   the object to extract — scalars returned directly, complex objects recursed
     * @param capture call-site overrides (nullable — null means use annotation + config defaults)
     * @return a map of safe key-value pairs, never null, may be empty
     */
    public Map<String, Object> extract(String key, Object value, Object capture) {
        if (!config.isEnabled()) return Collections.emptyMap();
        if (value == null) return Collections.singletonMap(key, null);

        try {
            int maxDepth = resolveMaxDepth(capture);
            Set<String> callSiteIncludes = resolveCallSiteIncludes(capture);
            Set<String> callSiteExcludes = resolveCallSiteExcludes(capture);

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] fieldCount = {0}; // mutable counter for field limit

            if (isScalar(value)) {
                return Collections.singletonMap(key, toSafeValue(value));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            extractObject(key, value, result, 0, maxDepth, callSiteIncludes,
                    callSiteExcludes, visited, fieldCount);
            return result;

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate extraction errors
            return Collections.singletonMap(key, "<extraction-error>");
        }
    }

    // ── Core recursive extractor ─────────────────────────────────────────────

    private void extractObject(String prefix, Object obj, Map<String, Object> result,
                               int depth, int maxDepth,
                               Set<String> callSiteIncludes, Set<String> callSiteExcludes,
                               Set<Object> visited, int[] fieldCount) {

        if (obj == null || depth > maxDepth || fieldCount[0] >= config.getMaxFields()) return;

        // Cycle detection
        if (!visited.add(obj)) return;

        Class<?> clazz = obj.getClass();

        // Layer 1: @FlowExclude on class → entire type is opaque
        if (hasFlowExclude(clazz)) return;

        // Layer 3: @FlowInclude on class → opt-in mode
        boolean optInMode = hasFlowInclude(clazz);

        // Handle Maps
        if (obj instanceof Map) {
            extractMap(prefix, (Map<?, ?>) obj, result, depth, maxDepth,
                    callSiteIncludes, callSiteExcludes, visited, fieldCount);
            return;
        }

        // Handle Collections/Arrays
        if (obj instanceof Collection) {
            extractCollection(prefix, (Collection<?>) obj, result, depth, maxDepth,
                    callSiteIncludes, callSiteExcludes, visited, fieldCount);
            return;
        }
        if (clazz.isArray()) {
            extractArray(prefix, obj, result, depth, maxDepth,
                    callSiteIncludes, callSiteExcludes, visited, fieldCount);
            return;
        }

        // Extract declared fields (walk up the hierarchy)
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldCount[0] >= config.getMaxFields()) return;

                // Skip static and synthetic fields
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || field.isSynthetic()) continue;

                String fieldName = field.getName();

                // Layer 2: @FlowExclude on field → always skip (cannot be overridden)
                if (hasFieldFlowExclude(field)) continue;

                // Layer 3: opt-in mode — only @FlowInclude fields
                if (optInMode && !hasFieldFlowInclude(field)) continue;

                // Layer 4: global exclude patterns (safety net)
                if (matchesGlobalExcludePattern(fieldName)) continue;

                // Layer 5: call-site FlowCapture overrides
                if (!callSiteIncludes.isEmpty() && !callSiteIncludes.contains(fieldName)) continue;
                if (callSiteExcludes.contains(fieldName)) continue;

                // Extract the field value
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(obj);
                    String fieldKey = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

                    if (fieldValue == null) {
                        result.put(fieldKey, null);
                        fieldCount[0]++;
                    } else if (isScalar(fieldValue)) {
                        result.put(fieldKey, toSafeValue(fieldValue));
                        fieldCount[0]++;
                    } else {
                        // Recurse into nested object
                        extractObject(fieldKey, fieldValue, result, depth + 1, maxDepth,
                                Collections.emptySet(), Collections.emptySet(), // call-site overrides don't cascade
                                visited, fieldCount);
                    }
                } catch (Throwable t) {
                    // Skip inaccessible fields silently
                }
            }
            current = current.getSuperclass();
        }
    }

    // ── Collection / Map / Array handlers ────────────────────────────────────

    private void extractMap(String prefix, Map<?, ?> map, Map<String, Object> result,
                            int depth, int maxDepth,
                            Set<String> callSiteIncludes, Set<String> callSiteExcludes,
                            Set<Object> visited, int[] fieldCount) {
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (fieldCount[0] >= config.getMaxFields()) return;
            String entryKey = prefix + "[" + String.valueOf(entry.getKey()) + "]";
            Object entryValue = entry.getValue();
            if (entryValue == null || isScalar(entryValue)) {
                result.put(entryKey, entryValue == null ? null : toSafeValue(entryValue));
                fieldCount[0]++;
            } else {
                extractObject(entryKey, entryValue, result, depth + 1, maxDepth,
                        Collections.emptySet(), Collections.emptySet(), visited, fieldCount);
            }
            if (++i >= config.getMaxFields()) return; // limit map entry count too
        }
    }

    private void extractCollection(String prefix, Collection<?> coll, Map<String, Object> result,
                                   int depth, int maxDepth,
                                   Set<String> callSiteIncludes, Set<String> callSiteExcludes,
                                   Set<Object> visited, int[] fieldCount) {
        result.put(prefix + ".size", coll.size());
        fieldCount[0]++;
        int i = 0;
        for (Object item : coll) {
            if (fieldCount[0] >= config.getMaxFields()) return;
            String itemKey = prefix + "[" + i + "]";
            if (item == null || isScalar(item)) {
                result.put(itemKey, item == null ? null : toSafeValue(item));
                fieldCount[0]++;
            } else {
                extractObject(itemKey, item, result, depth + 1, maxDepth,
                        Collections.emptySet(), Collections.emptySet(), visited, fieldCount);
            }
            i++;
        }
    }

    private void extractArray(String prefix, Object arr, Map<String, Object> result,
                              int depth, int maxDepth,
                              Set<String> callSiteIncludes, Set<String> callSiteExcludes,
                              Set<Object> visited, int[] fieldCount) {
        int len = java.lang.reflect.Array.getLength(arr);
        result.put(prefix + ".length", len);
        fieldCount[0]++;
        for (int i = 0; i < len; i++) {
            if (fieldCount[0] >= config.getMaxFields()) return;
            Object item = java.lang.reflect.Array.get(arr, i);
            String itemKey = prefix + "[" + i + "]";
            if (item == null || isScalar(item)) {
                result.put(itemKey, item == null ? null : toSafeValue(item));
                fieldCount[0]++;
            } else {
                extractObject(itemKey, item, result, depth + 1, maxDepth,
                        Collections.emptySet(), Collections.emptySet(), visited, fieldCount);
            }
        }
    }

    // ── Annotation helpers (cached) ──────────────────────────────────────────

    private static boolean hasFlowExclude(Class<?> clazz) {
        return classExcludeCache.computeIfAbsent(clazz,
                c -> hasAnnotationByName(c.getAnnotations(), FLOW_EXCLUDE));
    }

    private static boolean hasFlowInclude(Class<?> clazz) {
        return classIncludeCache.computeIfAbsent(clazz,
                c -> hasAnnotationByName(c.getAnnotations(), FLOW_INCLUDE));
    }

    private static boolean hasFieldFlowExclude(Field field) {
        return fieldExcludeCache.computeIfAbsent(field,
                f -> hasAnnotationByName(f.getAnnotations(), FLOW_EXCLUDE));
    }

    private static boolean hasFieldFlowInclude(Field field) {
        return fieldIncludeCache.computeIfAbsent(field,
                f -> hasAnnotationByName(f.getAnnotations(), FLOW_INCLUDE));
    }

    private static boolean hasAnnotationByName(Annotation[] annotations, String annotationName) {
        for (Annotation ann : annotations) {
            if (ann.annotationType().getName().equals(annotationName)) return true;
        }
        return false;
    }

    // ── Global pattern matching ──────────────────────────────────────────────

    /**
     * Checks if a field name matches any global exclude pattern.
     * Patterns are case-insensitive and support leading/trailing wildcards.
     * Examples: {@code *password*}, {@code *email*}, {@code *ssn*}
     */
    private boolean matchesGlobalExcludePattern(String fieldName) {
        List<String> patterns = config.getGlobalExcludePatterns();
        if (patterns == null || patterns.isEmpty()) return false;

        String lowerField = fieldName.toLowerCase();
        for (String pattern : patterns) {
            if (globMatches(pattern.toLowerCase(), lowerField)) return true;
        }
        return false;
    }

    /**
     * Simple glob matching supporting leading and trailing wildcards.
     * {@code *password*} matches "userPassword", "passwordHash", "password".
     */
    private static boolean globMatches(String pattern, String text) {
        if (pattern.equals("*")) return true;

        boolean startsWild = pattern.startsWith("*");
        boolean endsWild = pattern.endsWith("*");
        String core = pattern;
        if (startsWild) core = core.substring(1);
        if (endsWild && !core.isEmpty()) core = core.substring(0, core.length() - 1);

        if (core.isEmpty()) return true; // pattern was just "*" or "**"

        if (startsWild && endsWild) return text.contains(core);
        if (startsWild) return text.endsWith(core);
        if (endsWild) return text.startsWith(core);
        return text.equals(core);
    }

    // ── Type helpers ─────────────────────────────────────────────────────────

    private static boolean isScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isPrimitive()
                || value.getClass().isEnum();
    }

    /**
     * Converts a scalar value to a safe serializable form.
     * Enums are converted to their name string. Everything else is passed through.
     */
    private static Object toSafeValue(Object value) {
        if (value instanceof Enum) return ((Enum<?>) value).name();
        return value;
    }

    // ── FlowCapture resolution (reads via reflection to avoid compile dep on flow-sdk) ──

    private int resolveMaxDepth(Object capture) {
        if (capture != null) {
            try {
                int depth = (int) capture.getClass().getMethod("getMaxDepth").invoke(capture);
                if (depth >= 0) return depth;
            } catch (Throwable ignored) { }
        }
        return config.getMaxDepth();
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveCallSiteIncludes(Object capture) {
        if (capture != null) {
            try {
                Set<String> includes = (Set<String>) capture.getClass()
                        .getMethod("getIncludeFields").invoke(capture);
                if (includes != null && !includes.isEmpty()) return includes;
            } catch (Throwable ignored) { }
        }
        return Collections.emptySet();
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveCallSiteExcludes(Object capture) {
        if (capture != null) {
            try {
                Set<String> excludes = (Set<String>) capture.getClass()
                        .getMethod("getExcludeFields").invoke(capture);
                if (excludes != null) return excludes;
            } catch (Throwable ignored) { }
        }
        return Collections.emptySet();
    }
}

