package com.flow.agent.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NodeIdBuilderTest {

  @BeforeAll
  static void init() {
    ProxyResolver.init(List.of("com.flow"));
  }

  @Test
  void buildsNodeIdWithSimplifiedTypeNames() throws Exception {
    Method m = TestExample.class.getMethod("foo", List.class);
    String nodeId = NodeIdBuilder.build(TestExample.class, m);
    assertEquals(TestExample.class.getName() + "#foo(List<TestOrder>):Optional<TestResult>", nodeId);
  }
}

