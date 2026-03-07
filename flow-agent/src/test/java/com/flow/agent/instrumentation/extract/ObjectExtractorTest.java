package com.flow.agent.instrumentation.extract;

import com.flow.agent.config.AgentConfig.CaptureConfig;
import com.flow.sdk.FlowCapture;
import com.flow.sdk.FlowExclude;
import com.flow.sdk.FlowInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ObjectExtractor} — the PII-safe field extraction engine.
 */
class ObjectExtractorTest {

    private CaptureConfig config;
    private ObjectExtractor extractor;

    @BeforeEach
    void setUp() {
        config = new CaptureConfig();
        extractor = new ObjectExtractor(config);
    }

    // ── Test domain objects ──────────────────────────────────────────────────

    static class SimpleOrder {
        String orderId = "ORD-123";
        double total = 250.00;
        int itemCount = 3;
    }

    static class OrderWithPII {
        String orderId = "ORD-123";
        double total = 250.00;

        @FlowExclude
        String customerEmail = "john@example.com";

        @FlowExclude
        String creditCardNumber = "4111-1111-1111-1111";

        String status = "CONFIRMED";
    }

    @FlowExclude
    static class SensitiveType {
        String secretData = "TOP_SECRET";
        int code = 42;
    }

    @FlowInclude  // class-level: opt-in mode
    static class UserProfile {

        @FlowInclude
        String userId = "USR-456";

        @FlowInclude
        String tier = "GOLD";

        String email = "john@example.com";     // NOT opted in
        String phone = "555-1234";             // NOT opted in
        String ssn = "123-45-6789";            // NOT opted in
    }

    static class OrderWithNestedPII {
        String orderId = "ORD-789";
        SensitiveType sensitiveData = new SensitiveType();
        double total = 100.0;
    }

    static class Cart {
        String cartId = "CART-001";
        List<String> items = Arrays.asList("item1", "item2", "item3");
        double total = 75.50;

        @FlowExclude
        String internalNote = "test note";
    }

    static class NestedOrder {
        String orderId = "ORD-NESTED";
        InnerDetail detail = new InnerDetail();
    }

    static class InnerDetail {
        String description = "widget";
        double price = 19.99;
        DeepNested deep = new DeepNested();
    }

    static class DeepNested {
        String deepValue = "level-3";
    }

    enum OrderStatus { PENDING, CONFIRMED, SHIPPED }

    static class OrderWithEnum {
        String orderId = "ORD-ENUM";
        OrderStatus status = OrderStatus.CONFIRMED;
    }

    static class SelfRef {
        String name = "self";
        SelfRef self;
        SelfRef() { this.self = this; }
    }

    static class OrderWithGlobalPII {
        String orderId = "ORD-GLOBAL";
        String userPassword = "s3cret";        // matches *password*
        String emailAddress = "x@y.com";       // matches *email*
        String apiToken = "tok-123";           // matches *token*
        String normalField = "visible";
    }

    // ── Scalar tests ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scalar values")
    class ScalarTests {

        @Test
        void stringValue() {
            Map<String, Object> result = extractor.extract("orderId", "ORD-123", null);
            assertEquals(Map.of("orderId", "ORD-123"), result);
        }

        @Test
        void intValue() {
            Map<String, Object> result = extractor.extract("count", 42, null);
            assertEquals(Map.of("count", 42), result);
        }

        @Test
        void doubleValue() {
            Map<String, Object> result = extractor.extract("total", 250.00, null);
            assertEquals(Map.of("total", 250.00), result);
        }

        @Test
        void booleanValue() {
            Map<String, Object> result = extractor.extract("active", true, null);
            assertEquals(Map.of("active", true), result);
        }

        @Test
        void nullValue() {
            Map<String, Object> result = extractor.extract("missing", null, null);
            assertEquals(1, result.size());
            assertNull(result.get("missing"));
        }

        @Test
        void enumValue() {
            Map<String, Object> result = extractor.extract("status", OrderStatus.CONFIRMED, null);
            assertEquals(Map.of("status", "CONFIRMED"), result);
        }
    }

    // ── Simple object extraction ─────────────────────────────────────────────

    @Nested
    @DisplayName("Simple object extraction")
    class SimpleObjectTests {

        @Test
        void extractsAllFields() {
            Map<String, Object> result = extractor.extract("order", new SimpleOrder(), null);
            assertEquals("ORD-123", result.get("order.orderId"));
            assertEquals(250.00, result.get("order.total"));
            assertEquals(3, result.get("order.itemCount"));
        }

        @Test
        void enumFieldExtractedAsName() {
            Map<String, Object> result = extractor.extract("order", new OrderWithEnum(), null);
            assertEquals("ORD-ENUM", result.get("order.orderId"));
            assertEquals("CONFIRMED", result.get("order.status"));
        }
    }

    // ── @FlowExclude tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("@FlowExclude annotation")
    class FlowExcludeTests {

        @Test
        void excludesAnnotatedFields() {
            Map<String, Object> result = extractor.extract("order", new OrderWithPII(), null);
            assertEquals("ORD-123", result.get("order.orderId"));
            assertEquals(250.00, result.get("order.total"));
            assertEquals("CONFIRMED", result.get("order.status"));
            // PII fields excluded
            assertFalse(result.containsKey("order.customerEmail"));
            assertFalse(result.containsKey("order.creditCardNumber"));
        }

        @Test
        void excludesEntireTypeWhenAnnotatedOnClass() {
            Map<String, Object> result = extractor.extract("secret", new SensitiveType(), null);
            assertTrue(result.isEmpty(), "Entire @FlowExclude class should produce empty map");
        }

        @Test
        void excludesNestedSensitiveType() {
            Map<String, Object> result = extractor.extract("order", new OrderWithNestedPII(), null);
            assertEquals("ORD-789", result.get("order.orderId"));
            assertEquals(100.0, result.get("order.total"));
            // SensitiveType is @FlowExclude — none of its fields should appear
            assertFalse(result.containsKey("order.sensitiveData.secretData"));
            assertFalse(result.containsKey("order.sensitiveData.code"));
        }
    }

    // ── @FlowInclude tests (opt-in mode) ─────────────────────────────────────

    @Nested
    @DisplayName("@FlowInclude annotation (opt-in mode)")
    class FlowIncludeTests {

        @Test
        void onlyIncludesAnnotatedFields() {
            Map<String, Object> result = extractor.extract("user", new UserProfile(), null);
            assertEquals("USR-456", result.get("user.userId"));
            assertEquals("GOLD", result.get("user.tier"));
            // Non-opted fields excluded
            assertFalse(result.containsKey("user.email"));
            assertFalse(result.containsKey("user.phone"));
            assertFalse(result.containsKey("user.ssn"));
        }
    }

    // ── Global exclude patterns ──────────────────────────────────────────────

    @Nested
    @DisplayName("Global exclude patterns (safety net)")
    class GlobalPatternTests {

        @Test
        void excludesFieldsMatchingGlobalPatterns() {
            Map<String, Object> result = extractor.extract("order", new OrderWithGlobalPII(), null);
            assertEquals("ORD-GLOBAL", result.get("order.orderId"));
            assertEquals("visible", result.get("order.normalField"));
            // Global patterns should catch these even without @FlowExclude
            assertFalse(result.containsKey("order.userPassword"), "Should match *password*");
            assertFalse(result.containsKey("order.emailAddress"), "Should match *email*");
            assertFalse(result.containsKey("order.apiToken"), "Should match *token*");
        }

        @Test
        void customGlobalPatterns() {
            config.setGlobalExcludePatterns(List.of("*internal*"));
            extractor = new ObjectExtractor(config);

            Map<String, Object> result = extractor.extract("cart", new Cart(), null);
            assertEquals("CART-001", result.get("cart.cartId"));
            // internalNote is also @FlowExclude, but even without it, the pattern would catch it
            assertFalse(result.containsKey("cart.internalNote"));
        }
    }

    // ── FlowCapture call-site overrides ──────────────────────────────────────

    @Nested
    @DisplayName("FlowCapture call-site overrides")
    class FlowCaptureTests {

        @Test
        void includeOnlySpecificFields() {
            FlowCapture capture = FlowCapture.include("orderId", "total");
            Map<String, Object> result = extractor.extract("order", new SimpleOrder(), capture);
            assertEquals("ORD-123", result.get("order.orderId"));
            assertEquals(250.00, result.get("order.total"));
            assertFalse(result.containsKey("order.itemCount"));
        }

        @Test
        void excludeAdditionalFields() {
            FlowCapture capture = FlowCapture.exclude("itemCount");
            Map<String, Object> result = extractor.extract("order", new SimpleOrder(), capture);
            assertEquals("ORD-123", result.get("order.orderId"));
            assertEquals(250.00, result.get("order.total"));
            assertFalse(result.containsKey("order.itemCount"));
        }

        @Test
        void captureCannotOverrideFlowExclude() {
            // Even if call-site says "include customerEmail", @FlowExclude wins
            FlowCapture capture = FlowCapture.include("orderId", "customerEmail");
            Map<String, Object> result = extractor.extract("order", new OrderWithPII(), capture);
            assertEquals("ORD-123", result.get("order.orderId"));
            assertFalse(result.containsKey("order.customerEmail"),
                    "FlowCapture must never override @FlowExclude");
        }

        @Test
        void maxDepthOverride() {
            FlowCapture capture = FlowCapture.maxDepth(0);
            Map<String, Object> result = extractor.extract("order", new NestedOrder(), capture);
            // Depth 0 = only scalars at the top level
            assertEquals("ORD-NESTED", result.get("order.orderId"));
            // Nested object should NOT be recursed into
            assertFalse(result.containsKey("order.detail.description"));
        }
    }

    // ── Depth limiting ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Depth limiting")
    class DepthTests {

        @Test
        void respectsConfigMaxDepth() {
            config.setMaxDepth(1);
            extractor = new ObjectExtractor(config);

            Map<String, Object> result = extractor.extract("order", new NestedOrder(), null);
            assertEquals("ORD-NESTED", result.get("order.orderId"));
            assertEquals("widget", result.get("order.detail.description"));
            assertEquals(19.99, result.get("order.detail.price"));
            // depth=2 (DeepNested) should be cut off
            assertFalse(result.containsKey("order.detail.deep.deepValue"));
        }

        @Test
        void defaultDepthAllowsTwoLevels() {
            // Default maxDepth = 2
            Map<String, Object> result = extractor.extract("order", new NestedOrder(), null);
            assertEquals("ORD-NESTED", result.get("order.orderId"));
            assertEquals("widget", result.get("order.detail.description"));
            assertEquals("level-3", result.get("order.detail.deep.deepValue"));
        }
    }

    // ── Field count limiting ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Field count limiting")
    class FieldCountTests {

        @Test
        void stopsAtMaxFields() {
            config.setMaxFields(2);
            extractor = new ObjectExtractor(config);

            Map<String, Object> result = extractor.extract("order", new SimpleOrder(), null);
            assertEquals(2, result.size(), "Should stop at maxFields=2");
        }
    }

    // ── Cycle detection ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cycle detection")
    class CycleTests {

        @Test
        void handlesSelfReferencingObject() {
            Map<String, Object> result = extractor.extract("obj", new SelfRef(), null);
            assertEquals("self", result.get("obj.name"));
            // Should not infinite loop — self reference is detected and skipped
            assertFalse(result.toString().contains("StackOverflow"));
        }
    }

    // ── Collections and Maps ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Collections and Maps")
    class CollectionTests {

        @Test
        void extractsListFields() {
            Map<String, Object> result = extractor.extract("cart", new Cart(), null);
            assertEquals("CART-001", result.get("cart.cartId"));
            assertEquals(75.50, result.get("cart.total"));
            assertEquals(3, result.get("cart.items.size"));
            assertEquals("item1", result.get("cart.items[0]"));
            assertEquals("item2", result.get("cart.items[1]"));
            assertEquals("item3", result.get("cart.items[2]"));
            // @FlowExclude field excluded
            assertFalse(result.containsKey("cart.internalNote"));
        }

        @Test
        void extractsMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("name", "John");
            map.put("age", 30);

            Map<String, Object> result = extractor.extract("data", map, null);
            assertEquals("John", result.get("data[name]"));
            assertEquals(30, result.get("data[age]"));
        }
    }

    // ── Disabled capture ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Disabled capture")
    class DisabledTests {

        @Test
        void returnsEmptyWhenDisabled() {
            config.setEnabled(false);
            extractor = new ObjectExtractor(config);

            Map<String, Object> result = extractor.extract("order", new SimpleOrder(), null);
            assertTrue(result.isEmpty());
        }
    }
}

