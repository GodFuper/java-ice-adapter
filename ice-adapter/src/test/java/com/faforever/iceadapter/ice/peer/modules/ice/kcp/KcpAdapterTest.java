package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.jpower.kcp.netty.KcpOutput;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KcpAdapter}.
 * <p>
 * Tests verify that sending packets through KcpAdapter produces correct output
 * via the KcpOutput callback, and that incoming packets are properly decoded
 * and delivered via the handleData consumer.
 */
class KcpAdapterTest {

    private static final int CONV = 42;
    private static final int UPDATE_TICKS_TO_WAIT = 50;

    // Raw KCP output packets from A and from B (CopyOnWriteArrayList for safe concurrent iteration)
    private final List<byte[]> kcpOutputFromA = new CopyOnWriteArrayList<>();
    private final List<byte[]> kcpOutputFromB = new CopyOnWriteArrayList<>();

    // Decoded data received on each side (CopyOnWriteArrayList for safe concurrent iteration)
    private final List<String> decodedDataOnA = new CopyOnWriteArrayList<>();
    private final List<String> decodedDataOnB = new CopyOnWriteArrayList<>();

    private KcpAdapter adapterA;
    private KcpAdapter adapterB;

    @BeforeEach
    void setUp() {
        decodedDataOnA.clear();
        decodedDataOnB.clear();
        kcpOutputFromA.clear();
        kcpOutputFromB.clear();

        // Adapter A: its output goes into B's input, B's output goes into A's input
        KcpOutput outputA = (data, kcp) -> {
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            kcpOutputFromA.add(raw);
            // Feed into B's input (simulating the network)
            adapterB.onIncomingPacket(raw, 0, raw.length);
        };

        KcpOutput outputB = (data, kcp) -> {
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            kcpOutputFromB.add(raw);
            // Feed into A's input (simulating the network)
            adapterA.onIncomingPacket(raw, 0, raw.length);
        };

        adapterA = new KcpAdapter(
                CONV,
                "A",
                outputA,
                data -> {
                    decodedDataOnA.add(new String(data, StandardCharsets.UTF_8));
                }
        );

        adapterB = new KcpAdapter(
                CONV,
                "B",
                outputB,
                data -> {
                    decodedDataOnB.add(new String(data, StandardCharsets.UTF_8));
                }
        );

        adapterA.start();
        adapterB.start();
    }

    @AfterEach
    void tearDown() {
        if (adapterA != null) {
            adapterA.stop();
        }
        if (adapterB != null) {
            adapterB.stop();
        }
    }

    /**
     * Main test: send 10 packets and verify they are received through the output.
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Should send 10 packets and receive them through KcpOutput")
    void testSend10PacketsReceivedThroughOutput() {
        // Send 10 packets from A to B
        for (int i = 0; i < 10; i++) {
            String payload = "packet-" + i;
            adapterA.send(payload.getBytes(StandardCharsets.UTF_8));
        }

        // Wait until B has received all 10 decoded packets
        waitForDecodedData(() -> decodedDataOnB.size(), 10, 10_000);

        // Verify decoded data on B side
        assertEquals(10, decodedDataOnB.size(),
                "Expected 10 decoded packets on B, got: " + decodedDataOnB.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("packet-" + i, decodedDataOnB.get(i),
                    "Packet " + i + " payload mismatch");
        }

        // Also verify that KCP output was generated (some packets at least)
        assertTrue(kcpOutputFromA.size() > 0,
                "Expected some KCP output from A, got: " + kcpOutputFromA.size());
    }

    /**
     * Verify packet payload integrity — each packet arrives with correct content and order.
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Packet payloads should arrive in correct order with correct content")
    void testPacketPayloadIntegrity() {
        for (int i = 0; i < 10; i++) {
            String payload = "data-" + i + "-test";
            adapterA.send(payload.getBytes(StandardCharsets.UTF_8));
        }

        waitForDecodedData(() -> decodedDataOnB.size(), 10, 10_000);

        assertEquals(10, decodedDataOnB.size(), "Expected 10 decoded packets on B");
        for (int i = 0; i < 10; i++) {
            assertEquals("data-" + i + "-test", decodedDataOnB.get(i),
                    "Packet " + i + " content mismatch");
        }
    }

    /**
     * Test that onIncomingPacket delivers data through handleData callback.
     * Simulates receiving raw KCP-encoded bytes and verifying they are decoded.
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Incoming KCP packets should be decoded and delivered via handleData")
    void testOnIncomingPacketDeliversData() {
        String payload = "hello-from-a";
        adapterA.send(payload.getBytes(StandardCharsets.UTF_8));

        waitForDecodedData(() -> decodedDataOnB.size(), 1, 5_000);

        assertTrue(decodedDataOnB.size() > 0,
                "Adapter B should have received data via handleData, got: " + decodedDataOnB.size());
        boolean found = decodedDataOnB.contains(payload);
        assertTrue(found, "Expected to find '" + payload + "' in received data, got: " + decodedDataOnB);
    }

    /**
     * Test adapter lifecycle: start, send, stop, verify no crash after stop.
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Adapter should handle stop gracefully")
    void testAdapterStopGraceful() {
        // Send before stop
        adapterA.send("before-stop".getBytes(StandardCharsets.UTF_8));

        waitForDecodedData(() -> decodedDataOnB.size(), 1, 5_000);
        assertTrue(decodedDataOnB.size() > 0, "Should have received data before stop");

        // Stop adapter
        adapterA.stop();

        // Sending after stop should not crash
        assertDoesNotThrow(() -> adapterA.send("after-stop".getBytes(StandardCharsets.UTF_8)),
                "send() after stop() should not throw");
    }

    /**
     * Test sending two batches of packets with a pause between them.
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Should handle multiple batches of packets correctly")
    void testMultipleBatchesOfPackets() {
        // First batch: 5 packets
        for (int i = 0; i < 5; i++) {
            adapterA.send(("batch1-" + i).getBytes(StandardCharsets.UTF_8));
        }

        waitForDecodedData(() -> decodedDataOnB.size(), 5, 10_000);
        assertEquals(5, decodedDataOnB.size(), "First batch should produce 5 decoded packets");

        // Second batch: 5 more packets
        for (int i = 0; i < 5; i++) {
            adapterA.send(("batch2-" + i).getBytes(StandardCharsets.UTF_8));
        }

        waitForDecodedData(() -> decodedDataOnB.size(), 10, 10_000);
        assertEquals(10, decodedDataOnB.size(), "Second batch should produce 5 more decoded packets");

        // Verify batch 2 payloads (indices 5-9)
        for (int i = 5; i < 10; i++) {
            assertEquals("batch2-" + (i - 5), decodedDataOnB.get(i),
                    "Batch 2 packet " + (i - 5) + " mismatch");
        }
    }

    /**
     * Test sending data in the reverse direction (B to A).
     */
    @Test
    @Timeout(value = 30)
    @DisplayName("Should send packets in reverse direction (B to A)")
    void testReverseDirection() {
        for (int i = 0; i < 10; i++) {
            adapterB.send(("reverse-" + i).getBytes(StandardCharsets.UTF_8));
        }

        waitForDecodedData(() -> decodedDataOnA.size(), 10, 10_000);

        assertEquals(10, decodedDataOnA.size(), "Expected 10 decoded packets on A from B");
        for (int i = 0; i < 10; i++) {
            assertEquals("reverse-" + i, decodedDataOnA.get(i),
                    "Reverse packet " + i + " mismatch");
        }
    }

    /**
     * Waits until the supplier returns the target value, polling without calling update.
     * The KcpAdapter's ScheduledExecutorService handles updates on its own thread,
     * so we should NOT call update() from the test thread (causes ConcurrentModificationException).
     * Throws AssertionError on timeout.
     */
    private void waitForDecodedData(java.util.function.IntSupplier countSupplier, int target, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (countSupplier.getAsInt() >= target) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("Timed out after " + timeoutMs + "ms waiting for " + target + " decoded packets, "
                + "got: " + countSupplier.getAsInt());
    }
}
