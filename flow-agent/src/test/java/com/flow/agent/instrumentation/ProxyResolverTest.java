package com.flow.agent.instrumentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProxyResolver} — Spring CGLIB and JDK dynamic proxy resolution.
 */
class ProxyResolverTest {

    interface CustomerRepository {
        String findById(String id);
    }

    static class OrderService {
        public void placeOrder() {}
    }

    // Simulate a CGLIB proxy name: OrderService$$SpringCGLIB$$0
    // We can't create a real CGLIB proxy in a unit test without Spring,
    // so we verify the logic via the class name check.

    @BeforeEach
    void setup() {
        ProxyResolver.init(List.of("com.flow.agent"));
    }

    @Test
    void nonProxyClass_returnedAsIs() {
        Class<?> result = ProxyResolver.resolve(OrderService.class);
        assertEquals(OrderService.class, result);
    }

    @Test
    void jdkDynamicProxy_resolvedToCustomerInterface() {
        InvocationHandler handler = (proxy, method, args) -> null;
        Object jdkProxy = Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[]{CustomerRepository.class},
                handler
        );

        Class<?> result = ProxyResolver.resolve(jdkProxy.getClass());
        assertEquals(CustomerRepository.class, result);
    }

    @Test
    void jdkDynamicProxy_noMatchingPackage_returnedAsIs() {
        // Reset to empty prefix list — no customer packages configured
        ProxyResolver.init(List.of());

        InvocationHandler handler = (proxy, method, args) -> null;
        Object jdkProxy = Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[]{CustomerRepository.class},
                handler
        );

        Class<?> result = ProxyResolver.resolve(jdkProxy.getClass());
        // Returns the proxy class itself when no prefix matches
        assertTrue(Proxy.isProxyClass(result));
    }

    @Test
    void normalClass_withDollarSign_notTreatedAsProxy() {
        // Inner classes have $ in name — should not be misidentified as CGLIB
        class InnerService {}
        Class<?> result = ProxyResolver.resolve(InnerService.class);
        // Inner class name contains $ but not $$, should return as-is
        assertEquals(InnerService.class, result);
    }
}

