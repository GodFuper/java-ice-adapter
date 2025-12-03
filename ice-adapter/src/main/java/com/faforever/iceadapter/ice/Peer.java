package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;

import java.io.IOException;
import java.net.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.faforever.iceadapter.util.DatagramSocketUtils.MAX_SIZE_PACKET;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Data
@Slf4j
public class Peer {
    private final GameSession gameSession;

    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;
    private final boolean allowHost;
    private final boolean allowReflexive;
    private final boolean allowRelay;

    public volatile boolean closing = false;

    private final PeerIceModule ice = new PeerIceModule(this);
    private DatagramSocket faSocket; // Socket on which we are listening for FA / sending data to FA
    private final Lock lockSocketSend = new ReentrantLock();

    // Future handle for the FA listener task so we can cancel it cleanly
    private volatile CompletableFuture<?> faListenerFuture;

    public Peer(GameSession gameSession,
                int remoteId,
                String remoteLogin,
                boolean localOffer,
                int preferredPort,
                boolean allowHost,
                boolean allowReflexive,
                boolean allowRelay) {
        this.gameSession = gameSession;
        this.remoteId = remoteId;
        this.remoteLogin = remoteLogin;
        this.localOffer = localOffer;
        this.preferredPort = preferredPort;
        this.allowHost = allowHost;
        this.allowReflexive = allowReflexive;
        this.allowRelay = allowRelay;

        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}", getPeerIdentifier(), localOffer, preferredPort);

        faSocket = initForwarding(preferredPort);

        // Start FA listener and keep a handle so we can cancel it during shutdown
        faListenerFuture = CompletableFuture.runAsync(this::faListener, IceAdapter.getExecutor());

        if (localOffer) {
            CompletableFuture.runAsync(ice::initiateIce, IceAdapter.getExecutor());
        }
    }

    public int getLocalPort() {
        return faSocket.getLocalPort();
    }

    /**
     * Starts waiting for data from FA
     */
    @SneakyThrows(SocketException.class)
    private DatagramSocket initForwarding(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            DatagramSocketUtils.resizeBuffer(socket);
            log.debug("Now forwarding data to peer {}", getPeerIdentifier());
            return socket;
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
            throw e;
        }
    }

    /**
     * Forwards data received on ICE to FA
     * @param data
     * @param offset
     * @param length
     */
    void onIceDataReceived(byte[] data, int offset, int length) {
        // If we're closing or ICE isn't connected yet, drop packets to avoid races.
        if (closing) {
            log.debug("Dropping incoming ICE packet because peer is closing: {}", getPeerIdentifier());
            return;
        }

        // If ICE isn't established, drop early and log at trace level.
        try {
            if (!isConnected()) {
                log.trace("Dropping incoming ICE packet because ICE not connected yet: {}", getPeerIdentifier());
                return;
            }
        } catch (Exception ignore) {
            // If something odd happens reading state, still try to avoid NPEs and drop the packet
            log.trace("Couldn't read ICE state, dropping packet for {}", getPeerIdentifier());
            return;
        }

        LockUtil.executeWithLock(lockSocketSend, () -> {
            try {
                DatagramPacket packet = new DatagramPacket(
                        data, offset, length, InetAddress.getByName("127.0.0.1"), GPGNetServer.getLobbyPort());
                faSocket.send(packet);
            } catch (UnknownHostException e) {
                // should never happen for 127.0.0.1 but log at debug if it does
                log.debug("UnknownHostException when forwarding to FA for {}: {}", getPeerIdentifier(), e.toString());
            } catch (IOException e) {
                if (closing) {
                    log.debug(
                            "Ignoring error while sending packet because the connection was closed {}", getPeerIdentifier());
                } else {
                    log.error(
                            "Error while writing to local FA as peer (probably disconnecting from peer) {}",
                            getPeerIdentifier(),
                            e);
                    // If writing to FA fails repeatedly, request a reconnect to be safe
                    try {
                        ice.onConnectionLost();
                    } catch (Exception ex) {
                        log.debug("Error while requesting ICE reconnect", ex);
                    }
                }
            }
        });
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {
        byte[] data = new byte[MAX_SIZE_PACKET];
        while (!Thread.currentThread().isInterrupted() && IceAdapter.getGameSession() == gameSession && !closing) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length);
                faSocket.receive(packet);

                // Defensive copy of payload to avoid races with the receive buffer
                byte[] copy = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());

                // Forward to ICE - this method will drop packets if ICE isn't ready
                ice.onFaDataReceived(copy, copy.length);
            } catch (SocketException se) {
                // socket closed or network error
                if (closing) {
                    log.debug("FA listener shutting down for peer {}: {}", getPeerIdentifier(), se.toString());
                } else {
                    log.warn("SocketException in FA listener for peer {}: {}", getPeerIdentifier(), se.toString());
                    // Try to trigger ICE reconnect safely
                    try {
                        ice.onConnectionLost();
                    } catch (Exception ex) {
                        log.debug("Error while requesting ICE reconnect after socket exception", ex);
                    }
                }
                break;
            } catch (IOException e) {
                if (closing) {
                    log.debug(
                            "Ignoring error while receiving packet because the connection was closed as peer {}",
                            getPeerIdentifier());
                } else {
                    log.debug(
                            "Error while reading from local FA as peer (probably disconnecting from peer) {}",
                            getPeerIdentifier(),
                            e);
                    try {
                        ice.onConnectionLost();
                    } catch (Exception ex) {
                        log.debug("Error while requesting ICE reconnect after IO error", ex);
                    }
                }
                break;
            }
        }
        log.debug("No longer listening for messages from FA for peer {}", getPeerIdentifier());
    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return "%s(%d)".formatted(this.remoteLogin, this.remoteId);
    }

    public boolean isConnected() {
        return ice.isConnected();
    }

    public Optional<CandidateType> getLocalCandidateType() {
        return Optional.ofNullable(ice.getComponent())
                .map(Component::getSelectedPair)
                .map(CandidatePair::getLocalCandidate)
                .map(Candidate::getType);
    }

    public Optional<CandidateType> getRemoteCandidateType() {
        return Optional.ofNullable(ice.getComponent())
                .map(Component::getSelectedPair)
                .map(CandidatePair::getRemoteCandidate)
                .map(Candidate::getType);
    }

    public IceState getState() {
        return ice.getIceState();
    }

    public Optional<Float> getAverageRtt() {
        return Optional.ofNullable(ice.getConnectivityChecker())
                .map(PeerConnectivityCheckerModule::getAverageRTT);
    }

    public Optional<Long> getLastReceived() {
        return Optional.ofNullable(ice.getConnectivityChecker())
                .map(PeerConnectivityCheckerModule::getLastPacketReceived)
                .map(last -> System.currentTimeMillis() - last);
    }

    public Optional<Long> countEchosReceived() {
        return Optional.ofNullable(ice.getConnectivityChecker())
                .map(PeerConnectivityCheckerModule::getEchosReceived);
    }

    public Optional<Long> countInvalidEchosReceived() {
        return Optional.ofNullable(ice.getConnectivityChecker())
                .map(PeerConnectivityCheckerModule::getInvalidEchosReceived);
    }

    public void close() {
        if (closing) {
            return;
        }

        log.info("Closing peer for player {}", getPeerIdentifier());

        closing = true;

        // Cancel listener first to avoid it racing with ICE shutdown
        try {
            if (faListenerFuture != null) {
                faListenerFuture.cancel(true);
                // wait briefly for cancel to take effect
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            log.debug("Error cancelling faListenerFuture for {}: {}", getPeerIdentifier(), e.toString());
        }

        // Close ICE first so that it won't attempt to send packets into a closed faSocket
        try {
            ice.close();
        } catch (Exception e) {
            log.debug("Error closing ICE for {}: {}", getPeerIdentifier(), e.toString());
        }

        // Finally close the FA socket
        try {
            if (faSocket != null && !faSocket.isClosed()) {
                faSocket.close();
            }
        } catch (Exception e) {
            log.debug("Error closing faSocket for {}: {}", getPeerIdentifier(), e.toString());
        }

        log.info("Peer closed: {}", getPeerIdentifier());
    }
}

