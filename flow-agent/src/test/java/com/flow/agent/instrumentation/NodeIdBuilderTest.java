package com.flow.agent.instrumentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE most critical test in the project.
 *
 * <p>Validates that {@link NodeIdBuilder} produces the exact same nodeId strings as
 * {@code flow-java-adapter}'s scanner. A single character mismatch means events can never
 * be matched to graph nodes — the node stays dark forever.
 */
class NodeIdBuilderTest {

    // ── Sample classes mimicking customer code ───────────────────────────────

    static class OrderService {
        public String placeOrder(String orderId) { return orderId; }
        public void validateCart(String cartId) {}
        public List<String> getOrders() { return List.of(); }
        public Map<String, Object> getOrderMap(String id) { return Map.of(); }
        public Optional<String> findOrder(String id) { return Optional.empty(); }
        public void processOrder(String orderId, int quantity, boolean urgent) {}
        public int countOrders() { return 0; }
        public long getOrderTimestamp(String id) { return 0L; }
    }

    @BeforeEach
    void clearCache() throws Exception {
        // Clear the cache between tests so each test gets fresh computation
        var cacheField = NodeIdBuilder.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        ((java.util.Map<?, ?>) cacheField.get(null)).clear();

        // Ensure ProxyResolver has no prefix configured
        ProxyResolver.init(List.of());
    }

    // ── Basic format tests ───────────────────────────────────────────────────

    @Test
    void simpleStringParam_stringReturn() throws Exception {
        Method m = OrderService.class.getMethod("placeOrder", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#placeOrder(String):String",
                nodeId
        );
    }

    @Test
    void voidReturn() throws Exception {
        Method m = OrderService.class.getMethod("validateCart", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#validateCart(String):void",
                nodeId
        );
    }

    @Test
    void listReturnType() throws Exception {
        Method m = OrderService.class.getMethod("getOrders");
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#getOrders():List<String>",
                nodeId
        );
    }

    @Test
    void mapReturnType() throws Exception {
        Method m = OrderService.class.getMethod("getOrderMap", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#getOrderMap(String):Map<String, Object>",
                nodeId
        );
    }

    @Test
    void optionalReturnType() throws Exception {
        Method m = OrderService.class.getMethod("findOrder", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#findOrder(String):Optional<String>",
                nodeId
        );
    }

    @Test
    void multipleParams_includingPrimitive() throws Exception {
        Method m = OrderService.class.getMethod("processOrder", String.class, int.class, boolean.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#processOrder(String, int, boolean):void",
                nodeId
        );
    }

    @Test
    void primitiveReturn_int() throws Exception {
        Method m = OrderService.class.getMethod("countOrders");
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#countOrders():int",
                nodeId
        );
    }

    @Test
    void primitiveReturn_long() throws Exception {
        Method m = OrderService.class.getMethod("getOrderTimestamp", String.class);
        String nodeId = NodeIdBuilder.build(OrderService.class, m);
        assertEquals(
                "com.flow.agent.instrumentation.NodeIdBuilderTest$OrderService#getOrderTimestamp(String):long",
                nodeId
        );
    }

    @Test
    void noParams_voidReturn() throws Exception {
        class TestService {
            public void doSomething() {}
        }
        Method m = TestService.class.getMethod("doSomething");
        String nodeId = NodeIdBuilder.build(TestService.class, m);
        assertTrue(nodeId.endsWith("#doSomething():void"));
    }

    // ── simplifyType unit tests ───────────────────────────────────────────────

    @Test
    void simplifyType_javaLangString() {
        assertEquals("String", NodeIdBuilder.simplifyType("java.lang.String"));
    }

    @Test
    void simplifyType_javaUtilList() {
        assertEquals("List<String>",
                NodeIdBuilder.simplifyType("java.util.List<java.lang.String>"));
    }

    @Test
    void simplifyType_javaUtilMap() {
        assertEquals("Map<String, Object>",
                NodeIdBuilder.simplifyType("java.util.Map<java.lang.String, java.lang.Object>"));
    }

    @Test
    void simplifyType_fullyQualifiedCustomClass() {
        // New behaviour (Option A): ALL FQN prefixes are stripped — matches adapter's SignatureNormalizer.
        String result = NodeIdBuilder.simplifyType("com.example.OrderService");
        assertEquals("OrderService", result);
    }

    @Test
    void simplifyType_springKafkaTemplate() {
        // Verifies the key motivating case: Spring types stripped just like java.util types.
        String result = NodeIdBuilder.simplifyType(
                "org.springframework.kafka.core.KafkaTemplate<java.lang.String, java.lang.String>");
        assertEquals("KafkaTemplate<String, String>", result);
    }

    @Test
    void simplifyType_void() {
        assertEquals("void", NodeIdBuilder.simplifyType("void"));
    }

    @Test
    void simplifyType_primitives() {
        assertEquals("int", NodeIdBuilder.simplifyType("int"));
        assertEquals("long", NodeIdBuilder.simplifyType("long"));
        assertEquals("boolean", NodeIdBuilder.simplifyType("boolean"));
        assertEquals("double", NodeIdBuilder.simplifyType("double"));
    }

    // ── Cache test ────────────────────────────────────────────────────────────

    @Test
    void resultIsCachedAfterFirstCall() throws Exception {
        Method m = OrderService.class.getMethod("placeOrder", String.class);
        String first = NodeIdBuilder.build(OrderService.class, m);
        String second = NodeIdBuilder.build(OrderService.class, m);
        assertSame(first, second, "Cached result should be same object reference");
    }
}

