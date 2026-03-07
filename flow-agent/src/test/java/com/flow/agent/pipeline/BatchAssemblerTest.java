package com.flow.agent.pipeline;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.pipeline.RuntimeEvent;
import com.flow.agent.transport.HttpBatchSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BatchAssembler} — daemon thread that drains and flushes batches.
 * Uses a manual test double for HttpBatchSender to avoid Mockito/Java 25 issues.
 */
class BatchAssemblerTest {

    private EventRingBuffer ringBuffer;
    private FakeSender fakeSender;
    private BatchAssembler assembler;

    /** Manual test double that records all send() calls. */
    static class FakeSender extends HttpBatchSender {
        final CopyOnWriteArrayList<List<RuntimeEvent>> sentBatches = new CopyOnWriteArrayList<>();

        FakeSender() {
            super(createConfig(), createCb());
        }

        @Override
        public void send(String graphId, List<RuntimeEvent> events) {
            sentBatches.add(List.copyOf(events));
        }

        int totalEventsSent() {
            return sentBatches.stream().mapToInt(List::size).sum();
        }

        private static AgentConfig.ServerConfig createConfig() {
            AgentConfig.ServerConfig cfg = new AgentConfig.ServerConfig();
            cfg.setUrl("http://localhost:9999");
            cfg.setConnectTimeoutMs(1000);
            cfg.setReadTimeoutMs(1000);
            return cfg;
        }

        private static com.flow.agent.transport.CircuitBreaker createCb() {
            AgentConfig.CircuitBreakerConfig cbCfg = new AgentConfig.CircuitBreakerConfig();
            return new com.flow.agent.transport.CircuitBreaker(cbCfg);
        }
    }

    private RuntimeEvent makeEvent(String nodeId) {
        return new RuntimeEvent(
                "trace-1", "span-1", null, nodeId,
                "METHOD_ENTER", System.currentTimeMillis(), 0L, null);
    }

    @BeforeEach
    void setup() {
        ringBuffer = new EventRingBuffer(1024);
        fakeSender = new FakeSender();

        AgentConfig.PipelineConfig pipelineConfig = new AgentConfig.PipelineConfig();
        pipelineConfig.setBatchSize(5);
        pipelineConfig.setFlushIntervalMs(100);

        assembler = new BatchAssembler(ringBuffer, fakeSender, pipelineConfig, "test-graph");
    }

    @Test
    void countTrigger_flushesBatchWhenSizeReached() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            ringBuffer.offer(makeEvent("node" + i));
        }

        assembler.start();
        Thread.sleep(250);
        assembler.stop();

        assertEquals(5, fakeSender.totalEventsSent(), "All 5 events should have been sent");
    }

    @Test
    void timeTrigger_flushesBatchAfterInterval() throws InterruptedException {
        // Put fewer than batchSize events (< 5)
        ringBuffer.offer(makeEvent("nodeA"));
        ringBuffer.offer(makeEvent("nodeB"));

        assembler.start();
        Thread.sleep(300); // wait for time trigger (100ms interval)
        assembler.stop();

        assertEquals(2, fakeSender.totalEventsSent(),
                "Both events should have been flushed by time trigger");
    }

    @Test
    void emptyBuffer_doesNotCallSender() throws InterruptedException {
        assembler.start();
        Thread.sleep(250);
        assembler.stop();

        assertTrue(fakeSender.sentBatches.isEmpty(), "No batches should be sent for empty buffer");
    }

    @Test
    void stop_terminatesDaemonThread() throws InterruptedException {
        assembler.start();
        Thread.sleep(50);
        assembler.stop();
        Thread.sleep(100);
        // No assertion beyond not hanging — stop() must allow the thread to exit
    }
}

