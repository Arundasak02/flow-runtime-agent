package com.flow.sdk;

/**
 * Flow Checkpoint SDK.
 *
 * <p>Single class, zero dependencies. Customers add this JAR to their classpath and call
 * {@link #checkpoint(String, Object)} to attach key-value data to the currently executing
 * graph node.
 *
 * <p>Without the agent: all calls are no-ops — zero overhead.
 * <br>With the agent: the agent intercepts this method via ByteBuddy and emits a
 * {@code CHECKPOINT} event to FCS, attaching the data to the correct node on the
 * architecture graph.
 *
 * <p>Example usage:
 * <pre>{@code
 * public String placeOrder(String orderId) {
 *     Flow.checkpoint("orderId", orderId);
 *     // ... business logic
 * }
 * }</pre>
 */
public final class Flow {

    private Flow() {
        // Utility class — no instances
    }

    /**
     * Attach a key-value checkpoint to the currently executing graph node.
     *
     * <p>Without the agent this is a pure no-op. With the agent the call is intercepted
     * and a {@code CHECKPOINT} event is emitted to FCS.
     *
     * @param key   a short descriptive key (e.g. {@code "orderId"}, {@code "customerId"})
     * @param value the value to attach — will be serialized via {@code toString()}
     */
    public static void checkpoint(String key, Object value) {
        // No-op — the agent intercepts this method via ByteBuddy advice.
        // Do NOT add any implementation here; that would create a dependency on agent internals.
    }
}

