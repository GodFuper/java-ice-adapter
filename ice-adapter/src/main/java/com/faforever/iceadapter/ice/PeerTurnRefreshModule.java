package com.faforever.iceadapter.ice;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvest;
import org.ice4j.ice.harvest.TurnCandidateHarvest;
import org.ice4j.message.MessageFactory;
import org.ice4j.message.Request;
import org.ice4j.stack.TransactionID;

/**
 * Sends continuous refresh requests to the turn server
 */
@Slf4j
public class PeerTurnRefreshModule {

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
    private final PeerIceModule ice;

    @Getter
    private final RelayedCandidate candidate;

    private TurnCandidateHarvest harvest = null;

    private Thread refreshThread;
    private volatile boolean running = true;

    public PeerTurnRefreshModule(PeerIceModule ice, RelayedCandidate candidate) {
        this.ice = ice;
        this.candidate = candidate;

        try {
            harvest = (TurnCandidateHarvest) harvestField.get(candidate);
        } catch (IllegalAccessException e) {
            log.error("Could not get harvest from candidate.", e);
        }

        if (harvest == null) {
            log.warn("No TurnCandidateHarvest available for peer {}; TURN refresh will not run", ice.getPeer().getRemoteLogin());
            return;
        }

        refreshThread = Thread.startVirtualThread(this::refreshThread);
        log.debug("Started TURN refresh module for peer {}", ice.getPeer().getRemoteLogin());
    }

    private void refreshThread() {
        // Initial delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            return;
        }

        while (running && !Thread.currentThread().isInterrupted()) {
            if (harvest == null) {
                log.warn("TurnCandidateHarvest is null during refresh; stopping for peer {}", ice.getPeer().getPeerIdentifier());
                break;
            }

            Request refreshRequest = MessageFactory.createRefreshRequest(600); // Request max lifetime

            try {
                TransactionID tid = (TransactionID) sendRequestMethod.invoke(harvest, refreshRequest, false, null);
                log.debug("Sent TURN refresh request for peer {}", ice.getPeer().getPeerIdentifier());
            } catch (IllegalAccessException e) {
                log.error("Reflection access failed for sendRequest. TURN refresh will stop for peer {}",
                        ice.getPeer().getRemoteLogin(), e);
                break;
            } catch (InvocationTargetException e) {
                log.warn("TURN server rejected refresh request for peer {}", ice.getPeer().getPeerIdentifier(), e.getCause());
                // Continue — may recover after transient error
            } catch (Exception e) {
                log.warn("Unexpected error during TURN refresh for peer {}", ice.getPeer().getPeerIdentifier(), e);
            }

            try {
                Thread.sleep(REFRESH_INTERVAL);
            } catch (InterruptedException e) {
                log.debug("Refresh thread interrupted for peer {}", ice.getPeer().getPeerIdentifier());
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Closes the refresh module and sends a final REFRESH with LIFETIME=0 to free the TURN allocation.
     */
    public void close() {
        running = false;

        if (refreshThread != null) {
            refreshThread.interrupt();
        }

        if (harvest != null && candidate != null) {
            try {
                Request releaseRequest = MessageFactory.createRefreshRequest(0); // Free allocation
                sendRequestMethod.invoke(harvest, releaseRequest, false, null);
                log.debug("Sent TURN refresh with LIFETIME=0 to release allocation for peer {}", ice.getPeer().getRemoteLogin());
            } catch (Exception e) {
                log.warn("Failed to release TURN allocation for peer {}", ice.getPeer().getRemoteLogin(), e);
            } finally {
                harvest = null;
            }
        }

        log.debug("TURN refresh module stopped for peer {}", ice.getPeer().getRemoteLogin());
    }
}
