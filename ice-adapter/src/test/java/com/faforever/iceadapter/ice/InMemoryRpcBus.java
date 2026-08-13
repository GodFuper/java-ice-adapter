package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.RpcConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link RpcConnection} that delivers {@link CandidatesMessage}
 * directly to target peers via {@link Peer#iceMessageFromRPC(CandidatesMessage)}.
 *
 * <p>Peers are registered through {@link #registerPeer(int, Peer)} which stores them
 * in an internal map keyed by {@code peerId}. Messages are routed using
 * {@code CandidatesMessage.destId}.
 */
class InMemoryRpcBus implements RpcConnection {

    private final Map<Integer, Peer> peersById = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> peersConnected = new ConcurrentHashMap<>();
    private volatile boolean running;

    InMemoryRpcBus() {
        running = true;
    }

    /**
     * Register a peer with this bus. The peer will receive CandidatesMessages
     * addressed to its {@code peerId}.
     */
    void registerPeer(int peerId, Peer peer) {
        peersById.put(peerId, peer);
    }

    /**
     * Unregister a peer.
     */
    void unregisterPeer(int peerId) {
        peersById.remove(peerId);
    }

    /**
     * Start the bus. For InMemoryRpcBus this is a no-op as delivery is synchronous,
     * but kept for API compatibility with planned async variants.
     */
    void start() {
        // Synchronous delivery — no thread needed
    }

    /**
     * Stop the bus. No-op for synchronous implementation.
     */
    void stop() {
        running = false;
        peersById.clear();
    }

    @Override
    public void sendToRpc(CandidatesMessage message) {
        if (!running) {
            return;
        }
        Peer target = peersById.get(message.destId());
        if (target != null) {
            target.iceMessageFromRPC(message);
        }
    }

    @Override
    public void onConnected(Peer peer, boolean connected) {
        peersConnected.put(peer.getFromId(), connected);
    }
}
