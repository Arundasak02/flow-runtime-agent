package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterChain}, {@link PackageFilter}, {@link MethodExcludeFilter},
 * and {@link BridgeMethodFilter}.
 *
 * <h2>Filtering Flow — How It Works</h2>
 *
 * <p>The filter chain runs at <strong>class-load time only</strong> — there is zero per-call
 * overhead at runtime. When the JVM loads a class, ByteBuddy intercepts it and the filter chain
 * decides which classes and methods to instrument.
 *
 * <h3>Flow diagram (executed once per class load):</h3>
 * <pre>
 *   JVM loads class
 *       │
 *       ▼
 *   ┌─────────────────────────────────────┐
 *   │  1. PackageFilter.matches(className)│
 *   │     Is this class in a customer     │
 *   │     package (include) and NOT in    │
 *   │     an excluded sub-package?        │
 *   └────────────┬────────────────────────┘
 *          YES   │   NO → class loads normally, uninstrumented
 *                ▼
 *   ┌─────────────────────────────────────┐
 *   │  For each method in the class:      │
 *   │                                     │
 *   │  2. BridgeMethodFilter.shouldSkip() │
 *   │     Is it a JVM-generated bridge?   │
 *   │     YES → skip this method          │
 *   │                                     │
 *   │  3. MethodExcludeFilter.shouldSkip()│
 *   │     Is it toString/hashCode/equals? │
 *   │     Is it a getter/setter?          │
 *   │     Is it a constructor?            │
 *   │     Is it synthetic (lambda)?       │
 *   │     YES to any → skip this method   │
 *   └────────────┬────────────────────────┘
 *          PASS  │
 *                ▼
 *   ┌─────────────────────────────────────┐
 *   │  Method is INSTRUMENTED             │
 *   │  ByteBuddy inlines MethodAdvice     │
 *   │  (onEnter + onExit) into the method │
 *   └─────────────────────────────────────┘
 * </pre>
 *
 * <h3>Concrete Example — OrderService</h3>
 *
 * <p>Given this customer class and configuration:
 *
 * <pre>
 *   // Config:
 *   //   flow.packages.include = com.greens.order
 *   //   flow.packages.exclude = com.greens.order.config.internal
 *   //   flow.filter.skip-getters-setters = true
 *   //   flow.filter.skip-constructors = true
 *
 *   package com.greens.order.core;
 *
 *   public class OrderService {
 *       public OrderService() { ... }           // ← SKIPPED (constructor)
 *       public String placeOrder(String id) {}  // ← INSTRUMENTED ✅
 *       public void validateCart(String id) {}   // ← INSTRUMENTED ✅
 *       public String getOrderId() {}            // ← SKIPPED (getter)
 *       public void setStatus(String s) {}       // ← SKIPPED (setter)
 *       public boolean isActive() {}             // ← SKIPPED (is-getter)
 *       public String toString() {}              // ← SKIPPED (Object method)
 *       public int hashCode() {}                 // ← SKIPPED (Object method)
 *       private void helperMethod() {}           // ← INSTRUMENTED ✅ (skipPrivate=false)
 *   }
 * </pre>
 *
 * <p>And these classes would be filtered OUT at step 1 (package filter):
 *
 * <pre>
 *   org.springframework.web.DispatcherServlet     → NOT in com.greens.order → SKIP
 *   com.greens.order.config.internal.SecretConfig → EXCLUDED sub-package    → SKIP
 *   java.lang.String                              → NOT in com.greens.order → SKIP
 * </pre>
 *
 * <p>The result: only business-logic methods in customer packages are traced. Framework code,
 * boilerplate, and infrastructure are excluded. This keeps the agent's event volume low and
 * the architecture graph focused on meaningful method calls.
 *
 * <h3>Why This Matters</h3>
 *
 * <ul>
 *   <li><strong>Performance:</strong> Filtering at class-load time means zero per-invocation
 *       cost. A method is either instrumented or not — no runtime checks on every call.</li>
 *   <li><strong>Graph clarity:</strong> Only methods that exist in the static {@code flow.json}
 *       graph are instrumented. Getters, setters, toString etc. are not in the graph and would
 *       produce orphaned events if traced.</li>
 *   <li><strong>Safety:</strong> Never instrumenting framework code (Spring, Hibernate, etc.)
 *       eliminates the risk of interference with the customer's application.</li>
 * </ul>
 *
 * <p>Uses real {@link MethodDescription.ForLoadedMethod} instances instead of mocks
 * so the tests work on any JDK version (including Java 25).
 */
class FilterChainTest {

    private FilterChain filterChain;
    private PackageFilter packageFilter;
    private MethodExcludeFilter methodExcludeFilter;
    private BridgeMethodFilter bridgeMethodFilter;

    // ── Sample classes for reflection-based MethodDescription ─────────────────

    @SuppressWarnings("unused")
    static class SampleService {
        public void placeOrder() {}
        public String getOrderId() { return null; }
        public void setOrderId(String id) {}
        public boolean isActive() { return true; }
        public void processPayment() {}
        @Override public String toString() { return ""; }
        @Override public int hashCode() { return 0; }
        @Override public boolean equals(Object o) { return false; }
    }

    @BeforeEach
    void setup() {
        AgentConfig config = new AgentConfig();

        AgentConfig.PackagesConfig packages = new AgentConfig.PackagesConfig();
        packages.setInclude(List.of("com.greens.order"));
        packages.setExclude(List.of("com.greens.order.config.internal"));
        config.setPackages(packages);

        AgentConfig.FilterConfig filter = new AgentConfig.FilterConfig();
        filter.setSkipGettersSetters(true);
        filter.setSkipConstructors(true);
        filter.setSkipSynthetic(true);
        config.setFilter(filter);

        filterChain = FilterChain.from(config);
        packageFilter = new PackageFilter(config.getPackages());
        methodExcludeFilter = new MethodExcludeFilter(config.getFilter());
        bridgeMethodFilter = new BridgeMethodFilter();
    }

    // ── Step 1: Class-level filtering (PackageFilter) ──────────────────────
    //
    // The first gate. When the JVM loads a class, PackageFilter checks:
    //   1. Does the FQCN start with any of the configured include prefixes?
    //   2. Does the FQCN NOT start with any of the configured exclude prefixes?
    //
    // Example with config:
    //   include: ["com.greens.order"]
    //   exclude: ["com.greens.order.config.internal"]
    //
    //   "com.greens.order.core.OrderService"              → INCLUDED ✅
    //   "com.greens.order.config.internal.SecretConfig"    → EXCLUDED ❌
    //   "org.springframework.web.servlet.DispatcherServlet"→ NOT INCLUDED ❌

    @Test
    void includedPackage_accepted() {
        assertTrue(filterChain.shouldInstrumentClass("com.greens.order.core.OrderService"));
    }

    @Test
    void subpackageOfInclude_accepted() {
        assertTrue(filterChain.shouldInstrumentClass("com.greens.order.messaging.OrderProducer"));
    }

    @Test
    void excludedSubpackage_rejected() {
        assertFalse(filterChain.shouldInstrumentClass("com.greens.order.config.internal.SecretConfig"));
    }

    @Test
    void outsideInclude_rejected() {
        assertFalse(filterChain.shouldInstrumentClass("org.springframework.web.servlet.DispatcherServlet"));
    }

    @Test
    void javaLangClass_rejected() {
        assertFalse(filterChain.shouldInstrumentClass("java.lang.String"));
    }

    @Test
    void exactPackagePrefix_accepted() {
        assertTrue(packageFilter.matches("com.greens.order.OrderService"));
    }

    @Test
    void emptyClassName_rejected() {
        assertFalse(packageFilter.matches(""));
    }

    // ── Step 2 & 3: Method-level filtering (MethodExcludeFilter) ───────────
    //
    // After a class passes the package filter, each of its methods is checked.
    // MethodExcludeFilter skips methods that:
    //
    //   a) Are known Object methods (toString, hashCode, equals, etc.)
    //      → These are not in the static architecture graph.
    //
    //   b) Are getters/setters (getX, setX, isX where name.length > 3)
    //      → Boilerplate accessors that would flood the event pipeline.
    //
    //   c) Are constructors (<init>)
    //      → Not part of the architecture graph's method model.
    //
    //   d) Are synthetic (compiler-generated, e.g. lambda bridges)
    //      → Internal JVM artifacts, not in the static graph.
    //
    // Example for OrderService:
    //   placeOrder()   → PASS ✅  (business method — instrumented)
    //   getOrderId()   → SKIP ❌  (getter)
    //   setStatus()    → SKIP ❌  (setter)
    //   isActive()     → SKIP ❌  (boolean getter)
    //   toString()     → SKIP ❌  (Object method)

    @Test
    void toString_skipped() throws Exception {
        MethodDescription md = methodDesc("toString");
        assertTrue(methodExcludeFilter.shouldSkip(md), "toString should be excluded");
    }

    @Test
    void hashCode_skipped() throws Exception {
        MethodDescription md = methodDesc("hashCode");
        assertTrue(methodExcludeFilter.shouldSkip(md), "hashCode should be excluded");
    }

    @Test
    void equals_skipped() throws Exception {
        MethodDescription md = methodDesc(SampleService.class.getMethod("equals", Object.class));
        assertTrue(methodExcludeFilter.shouldSkip(md), "equals should be excluded");
    }

    @Test
    void getter_skipped() throws Exception {
        MethodDescription md = methodDesc("getOrderId");
        assertTrue(methodExcludeFilter.shouldSkip(md), "getOrderId should be excluded");
    }

    @Test
    void setter_skipped() throws Exception {
        MethodDescription md = methodDesc(SampleService.class.getMethod("setOrderId", String.class));
        assertTrue(methodExcludeFilter.shouldSkip(md), "setOrderId should be excluded");
    }

    @Test
    void isGetter_skipped() throws Exception {
        MethodDescription md = methodDesc("isActive");
        assertTrue(methodExcludeFilter.shouldSkip(md), "isActive should be excluded");
    }

    @Test
    void businessMethod_accepted() throws Exception {
        MethodDescription md = methodDesc("placeOrder");
        assertFalse(methodExcludeFilter.shouldSkip(md), "placeOrder should NOT be excluded");
    }

    @Test
    void processPayment_accepted() throws Exception {
        MethodDescription md = methodDesc("processPayment");
        assertFalse(methodExcludeFilter.shouldSkip(md), "processPayment should NOT be excluded");
    }

    // ── Step 2: Bridge method filtering (BridgeMethodFilter) ───────────────
    //
    // Bridge methods are synthetic methods generated by the JVM for:
    //   - Generic type covariance (e.g. Comparable<T>.compareTo bridge)
    //   - Covariant return types
    //
    // They are NOT in the static flow.json graph and must be skipped.
    // This check runs BEFORE MethodExcludeFilter (if bridge → skip immediately).

    @Test
    void nonBridgeMethod_notSkipped() throws Exception {
        MethodDescription md = methodDesc("placeOrder");
        assertFalse(bridgeMethodFilter.shouldSkip(md));
    }

    // ── Full FilterChain integration (all gates combined) ───────────────────
    //
    // These tests exercise the complete FilterChain (not individual filters).
    // They confirm that:
    //   PackageFilter → BridgeMethodFilter → MethodExcludeFilter
    // are wired together correctly.
    //
    // Example end-to-end for "com.greens.order.core.OrderService#placeOrder":
    //   1. PackageFilter: "com.greens.order.core.OrderService" starts with "com.greens.order" → PASS
    //   2. BridgeMethodFilter: placeOrder() is not a bridge method → PASS
    //   3. MethodExcludeFilter: "placeOrder" is not in EXCLUDED_NAMES,
    //      not a getter/setter, not a constructor, not synthetic → PASS
    //   Result: INSTRUMENTED ✅ — ByteBuddy inlines MethodAdvice into placeOrder()

    @Test
    void filterChain_acceptsBusinessMethod() throws Exception {
        TypeDescription type = TypeDescription.ForLoadedType.of(SampleService.class);
        MethodDescription md = methodDesc("placeOrder");
        assertTrue(filterChain.shouldInstrumentMethod(type, md));
    }

    @Test
    void filterChain_rejectsToString() throws Exception {
        TypeDescription type = TypeDescription.ForLoadedType.of(SampleService.class);
        MethodDescription md = methodDesc("toString");
        assertFalse(filterChain.shouldInstrumentMethod(type, md));
    }

    @Test
    void filterChain_rejectsGetter() throws Exception {
        TypeDescription type = TypeDescription.ForLoadedType.of(SampleService.class);
        MethodDescription md = methodDesc("getOrderId");
        assertFalse(filterChain.shouldInstrumentMethod(type, md));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MethodDescription methodDesc(String name) throws Exception {
        for (Method m : SampleService.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return new MethodDescription.ForLoadedMethod(m);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private MethodDescription methodDesc(Method m) {
        return new MethodDescription.ForLoadedMethod(m);
    }
}

