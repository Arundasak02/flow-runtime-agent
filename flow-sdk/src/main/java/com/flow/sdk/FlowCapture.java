package com.flow.sdk;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Call-site override for checkpoint field capture. Use this to narrow or adjust
 * which fields are extracted from an object <strong>at a specific checkpoint call</strong>,
 * on top of the class-level {@link FlowExclude} / {@link FlowInclude} annotations.
 *
 * <p>Annotations on the domain model set the <strong>permanent policy</strong>.
 * {@code FlowCapture} provides a <strong>per-call override</strong> — it can further
 * restrict, but it can <strong>never override an {@code @FlowExclude}</strong>.
 * A field excluded by annotation is always excluded.
 *
 * <p>Examples:
 * <pre>{@code
 * // Exclude specific fields at this call site
 * Flow.checkpoint("cart", cart, FlowCapture.exclude("internalNotes", "auditLog"));
 *
 * // Include only specific fields at this call site
 * Flow.checkpoint("cart", cart, FlowCapture.include("cartId", "total", "itemCount"));
 *
 * // Control extraction depth for nested objects
 * Flow.checkpoint("order", order, FlowCapture.maxDepth(1));
 *
 * // Combine: include specific fields with shallow depth
 * Flow.checkpoint("order", order,
 *     FlowCapture.include("orderId", "status", "total").withMaxDepth(1));
 * }</pre>
 *
 * <p><strong>Zero dependencies.</strong> This class is part of {@code flow-sdk} and
 * adds nothing to the customer's classpath.
 */
public final class FlowCapture {

    private final Set<String> includeFields;
    private final Set<String> excludeFields;
    private final int maxDepth;

    private FlowCapture(Set<String> includeFields, Set<String> excludeFields, int maxDepth) {
        this.includeFields = includeFields;
        this.excludeFields = excludeFields;
        this.maxDepth = maxDepth;
    }

    // ── Static factory methods ───────────────────────────────────────────────

    /**
     * Start with an include-list: only the named fields are captured (on top of annotation rules).
     */
    public static FlowCapture include(String... fields) {
        return new FlowCapture(
                new LinkedHashSet<>(Arrays.asList(fields)),
                Collections.emptySet(),
                -1 // -1 means "use config default"
        );
    }

    /**
     * Start with an exclude-list: the named fields are additionally excluded at this call site.
     */
    public static FlowCapture exclude(String... fields) {
        return new FlowCapture(
                Collections.emptySet(),
                new LinkedHashSet<>(Arrays.asList(fields)),
                -1
        );
    }

    /**
     * Limit extraction depth at this call site.
     *
     * @param depth 0 = scalar/toString only, 1 = one level of fields, 2 = default
     */
    public static FlowCapture maxDepth(int depth) {
        return new FlowCapture(Collections.emptySet(), Collections.emptySet(), depth);
    }

    // ── Fluent chaining ──────────────────────────────────────────────────────

    /**
     * Set the max extraction depth on an existing capture.
     */
    public FlowCapture withMaxDepth(int depth) {
        return new FlowCapture(this.includeFields, this.excludeFields, depth);
    }

    // ── Getters (read by the agent's ObjectExtractor via reflection or direct access) ──

    /** Returns the include-list, or empty if not set (meaning: include all per annotation rules). */
    public Set<String> getIncludeFields() { return includeFields; }

    /** Returns the exclude-list (additive to annotation-based exclusions). */
    public Set<String> getExcludeFields() { return excludeFields; }

    /**
     * Returns the max depth override, or {@code -1} if not set (use config default).
     */
    public int getMaxDepth() { return maxDepth; }

    @Override
    public String toString() {
        return "FlowCapture{include=" + includeFields
                + ", exclude=" + excludeFields
                + ", maxDepth=" + maxDepth + '}';
    }
}

