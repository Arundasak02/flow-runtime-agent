package com.flow.sdk;

/**
 * Flow Checkpoint SDK.
 *
 * <p>Single class, zero dependencies. Customers add this JAR to their classpath and call
 * {@link #checkpoint(String, Object)} to attach key-value data to the currently executing
 * graph node.
 *
 * <p>Without the agent: all calls are no-ops — zero overhead.
 * <br>With the agent: the agent intercepts these methods via ByteBuddy and emits
 * {@code CHECKPOINT} events to FCS, attaching the data to the correct node on the
 * architecture graph.
 *
 * <h3>Simple scalar checkpoint</h3>
 * <pre>{@code
 * Flow.checkpoint("orderId", orderId);
 * Flow.checkpoint("cart_total", cart.getTotal());
 * }</pre>
 *
 * <h3>Full object checkpoint (PII-safe)</h3>
 * <pre>{@code
 * // Captures all fields except those marked @FlowExclude on the domain model
 * Flow.checkpoint("cart", cart);
 *
 * // Further restrict at the call site — only these fields
 * Flow.checkpoint("cart", cart, FlowCapture.include("cartId", "total", "itemCount"));
 *
 * // Exclude additional fields at this call site
 * Flow.checkpoint("user", user, FlowCapture.exclude("internalNotes"));
 *
 * // Control extraction depth for nested objects
 * Flow.checkpoint("order", order, FlowCapture.maxDepth(1));
 * }</pre>
 *
 * <h3>PII protection — annotate once on the model</h3>
 * <pre>{@code
 * public class Cart {
 *     private String cartId;              // captured
 *     private double total;               // captured
 *
 *     {@literal @}FlowExclude
 *     private String customerEmail;       // NEVER captured — anywhere
 *
 *     {@literal @}FlowExclude
 *     private CreditCard paymentMethod;   // NEVER captured — anywhere
 * }
 * }</pre>
 *
 * @see FlowExclude
 * @see FlowInclude
 * @see FlowCapture
 */
public final class Flow {

    private Flow() {
        // Utility class — no instances
    }

    /**
     * Attach a key-value checkpoint to the currently executing graph node.
     *
     * <p>For scalar values (String, numbers, booleans), the value is emitted directly.
     * For complex objects, the agent extracts fields respecting {@link FlowExclude} /
     * {@link FlowInclude} annotations and global PII safety patterns.
     *
     * <p>Without the agent this is a pure no-op. With the agent the call is intercepted
     * and a {@code CHECKPOINT} event is emitted to FCS.
     *
     * @param key   a short descriptive key (e.g. {@code "orderId"}, {@code "cart"})
     * @param value the value to attach — scalars used directly, objects extracted field-by-field
     */
    public static void checkpoint(String key, Object value) {
        // No-op — the agent intercepts this method via ByteBuddy advice.
        // Do NOT add any implementation here; that would create a dependency on agent internals.
    }

    /**
     * Attach an object checkpoint with call-site capture overrides.
     *
     * <p>The {@link FlowCapture} parameter lets you further restrict which fields are
     * extracted <strong>at this specific call site</strong>, on top of the class-level
     * annotations. A {@link FlowCapture} can never override a {@link FlowExclude} —
     * excluded fields are always excluded.
     *
     * <p>Without the agent this is a pure no-op.
     *
     * @param key     a short descriptive key
     * @param value   the object whose fields to extract
     * @param capture call-site capture overrides (include/exclude/maxDepth)
     * @see FlowCapture#include(String...)
     * @see FlowCapture#exclude(String...)
     * @see FlowCapture#maxDepth(int)
     */
    public static void checkpoint(String key, Object value, FlowCapture capture) {
        // No-op — the agent intercepts this method via ByteBuddy advice.
    }
}

