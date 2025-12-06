package com.faforever.iceadapter.ice;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvest;
import org.ice4j.ice.harvest.TurnCandidateHarvest;
import org.ice4j.message.MessageFactory;
import org.ice4j.message.Request;
import org.ice4j.stack.TransactionID;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Sends continuous refresh requests to the turn server
 */
@Slf4j
@Deprecated
public class PeerTurnRefreshModule implements ModuleBase {

    private static final int REFRESH_INTERVAL = (int) Duration.ofMinutes(2).toMillis();

    private static Field harvestField;
    private static Method sendRequestMethod;

    static {
        try {
            harvestField = RelayedCandidate.class.getDeclaredField("turnCandidateHarvest");
            harvestField.setAccessible(true);
            sendRequestMethod = StunCandidateHarvest.class.getDeclaredMethod(
                    "sendRequest", Request.class, boolean.class, TransactionID.class);
            sendRequestMethod.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            log.error("Could not initialize harvestField for turn refreshing.", e);
        }
    }

    @Getter
    private final Peer peer;

    @Getter
    private final RelayedCandidate candidate;

    private TurnCandidateHarvest harvest = null;

    private Thread refreshThread;
    private volatile boolean running = true;

    public PeerTurnRefreshModule(Peer peer, RelayedCandidate candidate) {
        this.peer = peer;
        this.candidate = candidate;

        init();
    }

    private void init() {
        try {
            harvest = (TurnCandidateHarvest) harvestField.get(candidate);
        } catch (IllegalAccessException e) {
            log.error("Could not get harvest from candidate.", e);
        }

        if (harvest == null) {
            log.warn("No TurnCandidateHarvest available for peer {}; TURN refresh will not run", peer.getRemoteLogin());
            return;
        }

        start();
    }

    private void refreshThread() {
        // Initial delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            return;
        }

        while (running) {
            if (harvest == null) {
                log.warn("TurnCandidateHarvest is null during refresh; stopping for peer {}", peer.getPeerIdentifier());
                break;
            }

            try {
                sendRequest(600);
            } catch (Exception e) {
                break;
            }
            try {
                Thread.sleep(REFRESH_INTERVAL);
            } catch (InterruptedException e) {
                log.debug("Refresh thread interrupted for peer {}", peer.getPeerIdentifier());
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Closes the refresh module and sends a final REFRESH with LIFETIME=0 to free the TURN allocation.
     */
    public void close() {
        stop();
    }

    private void sendRequest(int lifetime) {
        Request refreshRequest = MessageFactory.createRefreshRequest(lifetime);
        try {
            TransactionID tid = (TransactionID) sendRequestMethod.invoke(harvest, refreshRequest, false, null);
            log.debug("Sent TURN refresh request for peer {}", peer.getPeerIdentifier());
        } catch (IllegalAccessException e) {
            log.error("Reflection access failed for sendRequest. TURN refresh will stop for peer {}",
                    peer.getRemoteLogin(), e);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            log.warn("TURN server rejected refresh request for peer {}", peer.getPeerIdentifier(), e.getCause());
            // Continue — may recover after transient error
        } catch (Exception e) {
            log.warn("Unexpected error during TURN refresh for peer {}", peer.getPeerIdentifier(), e);
        }
    }

    @Override
    public void start() {
        running = true;
        refreshThread = Thread.startVirtualThread(this::refreshThread);
        log.debug("Started TURN refresh module for peer {}", peer.getPeerIdentifier());
    }

    @Override
    public void stop() {
        running = false;

        if (!running) {
            return;
        }

        if (refreshThread != null) {
            refreshThread.interrupt();
        }

        if (harvest != null && candidate != null) {
            try {
                sendRequest(0);
            } catch (Exception e) {
                log.warn("Failed to release TURN allocation for peer {}", peer.getPeerIdentifier(), e);
            } finally {
                harvest = null;
            }
        }
        log.debug("TURN refresh module stopped for peer {}", peer.getPeerIdentifier());
    }
}
