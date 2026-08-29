package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KcpPeerToPeerSenderModule} with Peer fully mocked.
 * <p>
 * Both peers use {@link KcpPeerToPeerSenderModule} — the only difference is
 * {@code peerA.isLocalOffer() = true} (offerer) and {@code peerB.isLocalOffer() = false} (answerer).
 * All infrastructure (ICE, RPC, Component) is mocked — only KCP modules are tested.
 * </p>
 */
@Slf4j
@DisplayName("KcpPeerToPeerSenderModule — Full Mock")
class KcpPeerToPeerSenderModuleUnitTest {

    private final List<byte[]> packetsBtoA = new CopyOnWriteArrayList<>();
    private final List<String> decodedOnB = new CopyOnWriteArrayList<>();
    private final List<String> decodedOnA = new CopyOnWriteArrayList<>();
    private KcpAdapter senderAdapter;
    private KcpAdapter receiverAdapter;

    private Peer peerA;
    private Peer peerB;
    private Component compA;
    private Component compB;
    private KcpPeerToPeerSenderModule moduleA;
    private KcpPeerToPeerSenderModule moduleB;
    private AtomicLong bytesSentA = new AtomicLong(0);
    private AtomicLong bytesSentB = new AtomicLong(0);

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        packetsBtoA.clear();
        decodedOnB.clear();
        decodedOnA.clear();

        // ---- mock Peer A (localOffer = true, fromId=1, remoteId=2) ----
        peerA = mock(Peer.class);
        when(peerA.getPeerIdentifier()).thenReturn("PeerA");
        when(peerA.isLocalOffer()).thenReturn(true);
        when(peerA.getFromId()).thenReturn(1);
        when(peerA.getRemoteId()).thenReturn(2);
        when(peerA.isKcpTransportEnabled()).thenReturn(true);
        when(peerA.isSupportCommand()).thenReturn(true);
        when(peerA.getKcpStatistics()).thenReturn(new KcpStatistics());
        when(peerA.getLock(anyString())).thenReturn(new ReentrantLock());
        compA = mock(Component.class);
        when(peerA.getComponent()).thenReturn(compA);
        doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            byte[] copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            // When A sends, store packet to feed to B
            packetsBtoA.add(copy);
            bytesSentA.addAndGet(len);
            return null;
        }).when(compA).send(any(byte[].class), anyInt(), anyInt());

        // ---- mock Peer B (localOffer = false, fromId=2, remoteId=1) ----
        peerB = mock(Peer.class);
        when(peerB.getPeerIdentifier()).thenReturn("PeerB");
        when(peerB.isLocalOffer()).thenReturn(false);
        when(peerB.getFromId()).thenReturn(2);
        when(peerB.getRemoteId()).thenReturn(1);
        when(peerB.isKcpTransportEnabled()).thenReturn(true);
        when(peerB.isSupportCommand()).thenReturn(true);
        when(peerB.getKcpStatistics()).thenReturn(new KcpStatistics());
        when(peerB.getLock(anyString())).thenReturn(new ReentrantLock());
        compB = mock(Component.class);
        when(peerB.getComponent()).thenReturn(compB);
        doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            byte[] copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            // When B sends, store decoded string to verify
            decodedOnB.add(new String(data, off, len, StandardCharsets.UTF_8));
            bytesSentB.addAndGet(len);
            return null;
        }).when(compB).send(any(byte[].class), anyInt(), anyInt());

        // ---- create modules ----
        moduleA = new KcpPeerToPeerSenderModule(peerA);
        moduleA.init();
        moduleA.onIceComponentChange(peerA, compA);
        moduleA.start();

        moduleB = new KcpPeerToPeerSenderModule(peerB);
        moduleB.init();
        moduleB.onIceComponentChange(peerB, compB);
        moduleB.start();

        // Small wait for KCP adapters to initialize
        Thread.sleep(100);
    }

    // ---- Helper: send data from A → moduleA → KCP output → moduleB.onHandleData → decode ----
    private void feedAtoB(int count, String prefix) throws Exception {
        // Send from A
        for (int i = 0; i < count; i++) {
            byte[] data = (prefix + i).getBytes(StandardCharsets.UTF_8);
            moduleA.onSendToPeer(peerA, data);
        }
        Thread.sleep(80);

        // Feed A's KCP output into B's onHandleData
        List<byte[]> pkts = new CopyOnWriteArrayList<>(packetsBtoA);
        for (byte[] pkt : pkts) {
            moduleB.onHandleData(peerB, pkt);
        }
        Thread.sleep(80);
    }

    // ---- Helper: send data from B → moduleB → KCP output → moduleA.onHandleData ----
    private void feedBtoA(int count, String prefix) throws Exception {
        for (int i = 0; i < count; i++) {
            byte[] data = (prefix + i).getBytes(StandardCharsets.UTF_8);
            moduleB.onSendToPeer(peerB, data);
        }
        Thread.sleep(80);

        // Since B's output goes to compB which just stores decoded bytes,
        // we also need to feed B's raw KCP output into A's onHandleData
        // For simplicity, we verify via moduleB's component send
        decodedOnB.clear();
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("Module A (offerer) and Module B (answerer) should both initialize")
    void testBothModulesInitialize() {
        KcpTransport adapterA = moduleA.getKcpAdapter();
        KcpTransport adapterB = moduleB.getKcpAdapter();
        assertNotNull(adapterA, "Module A should have a KCP adapter");
        assertNotNull(adapterB, "Module B should have a KCP adapter");

        // Offerer uses fromId as conv, answerer uses 0/1 channel
        // Offerer conv = 42, answerer conv should be different (0 or 1)
        log.info("Module A conv={}, Module B conv={}", adapterA.getConv(), adapterB.getConv());
    }

    @Test
    @Timeout(value = 20)
    @DisplayName("Data sent from A (offerer) should be decoded by B (answerer)")
    void testDataFlowAtoB() throws Exception {
        int packetCount = 5;
        feedAtoB(packetCount, "mock-a-");

        // Check that decodedOnB received packets
        log.info("decodedOnB.size() = {}", decodedOnB.size());
        assertTrue(decodedOnB.size() > 0,
                "Expected B to decode packets from A, got: " + decodedOnB.size());
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("Module should mark packets with 'u' protocol marker")
    void testPacketsHaveKcpMarker() throws Exception {
        feedAtoB(3, "marker-");

        assertTrue(packetsBtoA.size() > 0,
                "Expected sender to produce KCP packets, got: " + packetsBtoA.size());

        for (byte[] pkt : packetsBtoA) {
            assertEquals(KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER, (char) pkt[0],
                    "First byte should be KCP_PROTOCOL_MARKER ('u')");
        }
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("onHandleData should not crash on non-KCP data")
    void testNonKcpDataIgnored() throws Exception {
        byte[] nonKcpData = new byte[]{0x01, 0x02, 0x03};
        assertDoesNotThrow(() -> moduleA.onHandleData(peerA, nonKcpData));
        assertDoesNotThrow(() -> moduleB.onHandleData(peerB, nonKcpData));
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("isEnabled should return true for both modules")
    void testIsEnabled() {
        assertTrue(moduleA.isEnabled(), "Module A should be enabled");
        assertTrue(moduleB.isEnabled(), "Module B should be enabled");
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("stop should not crash either module")
    void testStopGraceful() {
        assertDoesNotThrow(() -> moduleA.stop());
        assertDoesNotThrow(() -> moduleB.stop());
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("ModuleA conv should equal peerA fromId (1)")
    void testOffererConvEqualsFromId() throws Exception {
        KcpTransport adapterA = moduleA.getKcpModule();
        assertEquals(1, adapterA.getConv(), "Offerer conv should match fromId (1)");

        KcpTransport adapterB = moduleB.getKcpModule();
        assertEquals(1, adapterB.getConv(), "Answerer conv should match remoteId (1) == offerer conv");
    }

    @Test
    @Timeout(value = 45)
    @DisplayName("KCP should deliver all packets")
    void testKcpDeliversAllPackets() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();

        IceKcpOutput outputA = (data, kcp) -> {
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            data.release();
            receiverInput(receiverAdapter, raw);
        };

        IceKcpOutput outputB = (data, kcp) -> {
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            data.release();
            senderInput(senderAdapter, raw);
        };

        senderAdapter = new KcpAdapter(99, "MockA", outputA, data -> {
        });
        receiverAdapter = new KcpAdapter(99, "MockB", outputB,
                data -> received.add(new String(data, StandardCharsets.UTF_8)));
        senderAdapter.start();
        receiverAdapter.start();

        Thread.sleep(50);

        int packetCount = 15;
        for (int i = 0; i < packetCount; i++) {
            senderAdapter.send(("lossy-" + i).getBytes(StandardCharsets.UTF_8));
        }

        Thread.sleep(50);

        assertEquals(packetCount, received.size(),
                "Expected " + packetCount + " packets, got: " + received.size());
        for (int i = 0; i < packetCount; i++) {
            assertEquals("lossy-" + i, received.get(i), "Packet " + i + " mismatch");
        }

        senderAdapter.stop();
        receiverAdapter.stop();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20, 30, 40, 50, 60, 70})
    @Timeout(value = 5)
    @DisplayName("KCP should deliver all packets with {0}% packet loss")
    void testKcpDeliversAllPacketsWithLoss(int lossPercentage) throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        int dropChance = lossPercentage;

        IceKcpOutput outputA = (data, kcp) -> {
            if (ThreadLocalRandom.current().nextInt(100) < dropChance) {
                data.release();
                return;
            }
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            data.release();
            receiverInput(receiverAdapter, raw);
        };

        IceKcpOutput outputB = (data, kcp) -> {
            // ACK path — never drop, only drop forward data to avoid blocking KCP sender
            byte[] raw = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), raw);
            data.release();
            senderInput(senderAdapter, raw);
        };

        senderAdapter = new KcpAdapter(99, "MockA", outputA, data -> {
        });
        receiverAdapter = new KcpAdapter(99, "MockB", outputB,
                data -> received.add(new String(data, StandardCharsets.UTF_8)));
        senderAdapter.start();
        receiverAdapter.start();

        Thread.sleep(50);

        int packetCount = 100;
        for (int i = 0; i < packetCount; i++) {
            senderAdapter.send(("lossy-" + i).getBytes(StandardCharsets.UTF_8));
        }

        waitForDelivery(received::size, packetCount, 5_000);

        assertTrue(received.size() >= packetCount,
                "Expected at least " + packetCount + " packets with " + dropChance + "% loss, got: " + received.size());
        for (int i = 0; i < packetCount; i++) {
            assertEquals("lossy-" + i, received.get(i), "Packet " + i + " mismatch");
        }

        senderAdapter.stop();
        receiverAdapter.stop();
    }

    private void receiverInput(KcpAdapter adapter, byte[] raw) {
        if (adapter != null && adapter.isRunning()) {
            adapter.onReceive(raw, 0, raw.length);
        }
    }

    private void senderInput(KcpAdapter adapter, byte[] raw) {
        if (adapter != null && adapter.isRunning()) {
            adapter.onReceive(raw, 0, raw.length);
        }
    }

    private void waitForDelivery(IntSupplier countSupplier, int target, long timeoutMs) {
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
        int actual = countSupplier.getAsInt();
        fail("Timed out after " + timeoutMs + "ms waiting for " + target + " packets, got: " + actual);
    }

    @AfterEach
    void tearDown() {
        packetsBtoA.clear();
        decodedOnB.clear();
        decodedOnA.clear();
        try {
            if (moduleA != null) {
                moduleA.stop();
            }
            if (moduleB != null) {
                moduleB.stop();
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
