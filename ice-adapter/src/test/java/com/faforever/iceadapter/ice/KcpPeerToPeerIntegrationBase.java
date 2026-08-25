package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.base.InMemoryRpcBus;
import com.faforever.iceadapter.ice.base.TestGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for KCP peer-to-peer integration tests.
 * <p>
 * PEER_LISTENER_MODULE is disabled by default — tests inject their own listener module
 * (e.g. {@link DroppingPeerToPeerListenerModule}, {@link RandomDroppingPeerToPeerListenerModule}).
 * </p>
 */
@Slf4j
abstract class KcpPeerToPeerIntegrationBase {

    protected static final String LOCAL_IP = "127.0.0.1";
    protected static final int NUM_PACKETS = 10;
    protected static final long DATA_TIMEOUT_MS = 15_000;
    protected static final long KCP_INIT_DELAY_MS = 500;

    protected InMemoryRpcBus bus;
    protected InMemoryDatagramSocket socketA;
    protected InMemoryDatagramSocket socketB;
    protected TestGameSession gameA;
    protected TestGameSession gameB;
    protected Peer peerA;
    protected Peer peerB;

    @BeforeEach
    void setUp() throws SocketException, InterruptedException {
        bus = new InMemoryRpcBus();
        bus.start();

        socketA = new InMemoryDatagramSocket();
        socketB = new InMemoryDatagramSocket();

        IceOptions optionsA = new IceOptions(
                1, 0, "PlayerA", 0, 0, 0, false, false, false, 0, 0, 250.0, null,
                true, true, false, true, PeerSendMode.KCP_ONLY);
        IceOptions optionsB = new IceOptions(
                2, 0, "PlayerB", 0, 0, 0, false, false, false, 0, 0, 250.0, null,
                true, true, false, true, PeerSendMode.KCP_ONLY);

        Set<PeerModule> disabled = getDefaultDisabledModules();

        gameA = new TestGameSession(bus, optionsA, disabled);
        gameA.setLobbyPort(socketA.getLocalPort());

        gameB = new TestGameSession(bus, optionsB, disabled);
        gameB.setLobbyPort(socketB.getLocalPort());

        // A offers, B answers
        gameA.connectToPeer("PlayerB", 2, true, 0, AllowCombination.ALL);
        gameB.connectToPeer("PlayerA", 1, false, 0, AllowCombination.ALL);

        peerA = gameA.getPeer(2).get();
        peerB = gameB.getPeer(1).get();

        assertNotNull(peerA, "Peer A should exist");
        assertNotNull(peerB, "Peer B should exist");

        bus.registerPeer(peerA.getFromId(), peerA);
        bus.registerPeer(peerB.getFromId(), peerB);

        sleep(100);
        try {
            socketA.connect(InetAddress.getByName(LOCAL_IP), peerA.getFaSocket().getLocalPort());
            assertTrue(socketA.isConnected());
            socketB.connect(InetAddress.getByName(LOCAL_IP), peerB.getFaSocket().getLocalPort());
            assertTrue(socketB.isConnected());

            socketA.start();
            socketB.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        awaitIceReady(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A should be connected, state=" + peerA.getIceState());
        assertTrue(peerB.isConnected(), "Peer B should be connected, state=" + peerB.getIceState());

        socketA.clear();
        socketB.clear();
    }

    @AfterEach
    void tearDown() {
        gameA.close();
        gameB.close();
        bus.stop();
        socketA.close();
        socketB.close();
    }

    /**
     * Override in subclasses to control which modules are disabled.
     * PEER_LISTENER_MODULE is always included in the default set.
     */
    protected Set<PeerModule> getDefaultDisabledModules() {
        return Set.of(
                PeerModule.CONNECTION_CHECKER_MODULE,
                PeerModule.PEER_TURN_REFRESHER_MODULE,
                PeerModule.RELAY_CLIENT_MODULE,
                PeerModule.RELAY_SERVER_MODULE,
                PeerModule.AUTO_RELAY_CALCULATE_RTT,
                PeerModule.PEER_LISTENER_MODULE);
    }

    /**
     * Inject a listener module into the given peer.
     * The module must be started manually via {@link PeerToPeerListenerModule#onIceComponentChange}.
     * Waits for the ICE component to be available before injecting.
     */
    protected void injectListenerModule(Peer peer, PeerToPeerListenerModule listener) {
        listener.init();
        peer.getModules().put(PeerModule.PEER_LISTENER_MODULE, listener);
        // Wait for component to be ready (may be null at time of injection)
        waitForComponent(peer, 5000);
        if (peer.getComponent() != null) {
            listener.onIceComponentChange(peer, peer.getComponent());
        }
    }

    private void waitForComponent(Peer peer, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (peer.getComponent() != null) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    protected void waitForReceived(InMemoryDatagramSocket socket, int expected, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (socket.getReceivedBytes().size() >= expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int actual = socket.getReceivedBytes().size();
        fail("Timed out after " + timeoutMs + "ms waiting for " + expected
                + " packets, got: " + actual);
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitIceReady(Peer peerA, Peer peerB) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (peerA.isConnected() && peerB.isConnected()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("ICE connection timeout (A=" + peerA.getIceState() + ", B=" + peerB.getIceState() + ")");
    }
}
