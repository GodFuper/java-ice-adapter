package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.IceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Map;
import java.util.concurrent.*;

import static org.ice4j.ice.Agent.PROPERTY_ICE_PROCESSING_STATE;

@Slf4j
@RequiredArgsConstructor
public class PeerConnectionSuccessMonitor implements PropertyChangeListener, AgentSuccessMonitor {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<CandidatePair, ScheduledFuture<?>> pendingPairs = new ConcurrentHashMap<>();
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

        for (CandidatePair pair : mediaStream.getCheckList()) {
            scheduleTimeoutCheck(pair);
        }
        return future;
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {

        if (PROPERTY_ICE_PROCESSING_STATE.equals(event.getPropertyName())) {
            IceProcessingState state = (IceProcessingState) event.getNewValue();

            if (state.isEstablished()) {

                IceUtils.getFirstComponent(peer.getMediaStream())
                        .map(Component::getSelectedPair)
                        .ifPresent(this::onPairSucceeded);
            }

            if (state == IceProcessingState.FAILED) {
                agentConnectionFailed();
            }
        }
    }

    private void scheduleTimeoutCheck(CandidatePair pair) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (pendingPairs.remove(pair) != null) {
                onPairTimeout(pair);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        pendingPairs.put(pair, future);
    }

    private void onPairSucceeded(CandidatePair pair) {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.debug("✅ Successful: Pair is SUCCEEDED: {}", pair);
        future.complete(true);
    }

    private void onPairTimeout(CandidatePair pair) {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.info("❌ Timeout: Pair not state SUCCEEDED by {} ms: {}", timeoutMs, pair);
        future.complete(true);
    }

    private void agentConnectionFailed() {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.warn("❌ Agent state failed");
        future.complete(false);
    }

    public void shutdown() {
        scheduler.shutdown();
        pendingPairs.values().forEach(f -> f.cancel(true));
        pendingPairs.clear();
    }
}
