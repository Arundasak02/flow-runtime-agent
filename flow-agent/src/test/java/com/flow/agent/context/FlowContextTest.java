package com.flow.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlowContext} — ThreadLocal trace context management.
 */
class FlowContextTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        FlowContext.clear();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    void getOrInit_createsNewContext_whenNoneExists() {
        FlowContext ctx = FlowContext.getOrInit();
        assertNotNull(ctx);
        assertNotNull(ctx.getTraceId());
        assertFalse(ctx.getTraceId().isEmpty());
    }

    @Test
    void getOrInit_returnsSameInstance_whenCalledTwice() {
        FlowContext ctx1 = FlowContext.getOrInit();
        FlowContext ctx2 = FlowContext.getOrInit();
        assertSame(ctx1, ctx2);
        assertEquals(ctx1.getTraceId(), ctx2.getTraceId());
    }

    @Test
    void current_returnsNull_whenNoContextSet() {
        assertNull(FlowContext.current());
    }

    @Test
    void current_returnsContext_afterGetOrInit() {
        FlowContext.getOrInit();
        assertNotNull(FlowContext.current());
    }

    @Test
    void clear_removesContext() {
        FlowContext.getOrInit();
        assertNotNull(FlowContext.current());
        FlowContext.clear();
        assertNull(FlowContext.current());
    }

    @Test
    void initNewTrace_createsNewContext_withDifferentTraceId() {
        FlowContext ctx1 = FlowContext.getOrInit();
        String traceId1 = ctx1.getTraceId();

        FlowContext.initNewTrace();

        FlowContext ctx2 = FlowContext.current();
        assertNotNull(ctx2);
        assertNotEquals(traceId1, ctx2.getTraceId(), "initNewTrace must produce a new traceId");
    }

    // ── Span stack ───────────────────────────────────────────────────────────

    @Test
    void pushAndPop_singleSpan() {
        FlowContext ctx = FlowContext.getOrInit();
        SpanInfo span = new SpanInfo("span1", null, "nodeId1", 123L);

        ctx.pushSpan(span);
        assertEquals("span1", ctx.currentSpanId());

        SpanInfo popped = ctx.popSpan();
        assertSame(span, popped);
        assertNull(ctx.currentSpanId());
    }

    @Test
    void pushAndPop_nestedSpans_LIFO() {
        FlowContext ctx = FlowContext.getOrInit();
        SpanInfo span1 = new SpanInfo("span1", null, "nodeA", 1L);
        SpanInfo span2 = new SpanInfo("span2", "span1", "nodeB", 2L);

        ctx.pushSpan(span1);
        ctx.pushSpan(span2);

        assertEquals("span2", ctx.currentSpanId()); // top of stack

        SpanInfo popped = ctx.popSpan();
        assertEquals("span2", popped.getSpanId());

        assertEquals("span1", ctx.currentSpanId()); // now span1 is on top

        popped = ctx.popSpan();
        assertEquals("span1", popped.getSpanId());

        assertTrue(ctx.isSpanStackEmpty());
    }

    @Test
    void popSpan_emptyStack_returnsNull() {
        FlowContext ctx = FlowContext.getOrInit();
        assertNull(ctx.popSpan());
    }

    @Test
    void isSpanStackEmpty_trueWhenEmpty() {
        FlowContext ctx = FlowContext.getOrInit();
        assertTrue(ctx.isSpanStackEmpty());
    }

    @Test
    void isSpanStackEmpty_falseAfterPush() {
        FlowContext ctx = FlowContext.getOrInit();
        ctx.pushSpan(new SpanInfo("s1", null, "n1", 0L));
        assertFalse(ctx.isSpanStackEmpty());
    }

    @Test
    void currentNodeId_returnsTopNodeId() {
        FlowContext ctx = FlowContext.getOrInit();
        ctx.pushSpan(new SpanInfo("s1", null, "nodeA", 0L));
        ctx.pushSpan(new SpanInfo("s2", "s1", "nodeB", 0L));
        assertEquals("nodeB", ctx.currentNodeId());
    }

    // ── Thread isolation ──────────────────────────────────────────────────────

    @Test
    void threadIsolation_differentThreadsHaveDifferentContexts() throws InterruptedException {
        FlowContext mainCtx = FlowContext.getOrInit();
        String mainTraceId = mainCtx.getTraceId();

        String[] threadTraceId = new String[1];
        Thread t = new Thread(() -> {
            FlowContext ctx = FlowContext.getOrInit();
            threadTraceId[0] = ctx.getTraceId();
            FlowContext.clear();
        });
        t.start();
        t.join();

        assertNotNull(threadTraceId[0]);
        assertNotEquals(mainTraceId, threadTraceId[0],
                "Each thread must have its own independent traceId");
    }

    @Test
    void clearOnOneThread_doesNotAffectOtherThread() throws InterruptedException {
        FlowContext.getOrInit(); // init on main thread

        boolean[] hadContext = new boolean[1];
        Thread t = new Thread(() -> {
            FlowContext ctx = FlowContext.getOrInit();
            hadContext[0] = (ctx != null);
            FlowContext.clear();
        });
        t.start();
        t.join();

        // Main thread context should still be present
        assertNotNull(FlowContext.current());
        assertTrue(hadContext[0]);
    }
}

