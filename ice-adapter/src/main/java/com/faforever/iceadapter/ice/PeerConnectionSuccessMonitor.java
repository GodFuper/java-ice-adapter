package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.IceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.*;

import static org.ice4j.ice.Agent.PROPERTY_ICE_PROCESSING_STATE;

@Slf4j
@RequiredArgsConstructor
public class PeerConnectionSuccessMonitor implements PropertyChangeListener, AgentSuccessMonitor {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> scheduledFuture;
    private CompletableFuture<Boolean> future;
    private String name = "";
    private final long timeoutMs;
    private final Peer peer;

    @Override
    public CompletableFuture<Boolean> start() {
        future = new CompletableFuture<>();
        if (peer == null) {
            log.error("Peer is null. Aborting.");
            future.complete(false);
            return future;
        }
        Agent agent = peer.getAgent();
        if (agent == null) {
            log.error("Agent is null. Aborting.");
            future.complete(false);
            return future;
        }
        IceMediaStream mediaStream = peer.getMediaStream();
        if (mediaStream == null) {
            log.error("Media stream is null. Aborting.");
            future.complete(false);
            return future;
        }

        name = peer.getPeerIdentifier();

        agent.addStateChangeListener(this);
        mediaStream.addPairChangeListener(this);

        scheduledFuture = scheduler.schedule(this::agentTimeoutFailed, timeoutMs, TimeUnit.MILLISECONDS);

        return future;
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {

        if (PROPERTY_ICE_PROCESSING_STATE.equals(event.getPropertyName())) {
            IceProcessingState state = (IceProcessingState) event.getNewValue();

            if (state.isEstablished() && !isConnected()) {
                IceUtils.getFirstComponent(peer.getMediaStream())
                        .map(Component::getSelectedPair)
                        .ifPresent(this::onPairSucceeded);
            }

            if (state == IceProcessingState.FAILED) {
                agentConnectionFailed();
            }
        }
    }

    private boolean isConnected() {
        return peer.getIceState() == IceState.CONNECTED && peer.getComponent() != null;
    }

    private void onPairSucceeded(CandidatePair pair) {
        Thread.currentThread().setName(name);
        log.debug("✅ Successful: Pair is SUCCEEDED: {}", pair);
        future.complete(true);
    }

    private void agentTimeoutFailed() {
        Thread.currentThread().setName(name);
        log.info("❌ Timeout: Time has run out to try to connect by {} ms", timeoutMs);
        future.complete(false);
    }

    private void agentConnectionFailed() {
        Thread.currentThread().setName(name);
        log.warn("❌ Agent state failed");
        future.complete(false);
    }

    public void shutdown() {
        scheduler.shutdown();
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }
}
