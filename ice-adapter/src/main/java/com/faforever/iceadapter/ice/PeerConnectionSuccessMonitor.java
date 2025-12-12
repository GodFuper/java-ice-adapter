package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Map;
import java.util.concurrent.*;

import static org.ice4j.ice.Agent.PROPERTY_ICE_PROCESSING_STATE;
import static org.ice4j.ice.IceMediaStream.PROPERTY_PAIR_NOMINATED;

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

//            if(state.isEstablished()) {
//
//                IceUtils.getFirstComponent(peer.getMediaStream())
//                        .map(Component::getSelectedPair)
//                        .ifPresent(this::onPairSucceeded);
//            }

            if (state == IceProcessingState.FAILED) {
                agentConnectionFailed();
            }
        }
//
        if (PROPERTY_PAIR_NOMINATED.equals(event.getPropertyName())) {
            CandidatePair pair = (CandidatePair) event.getSource();
            Boolean isNominated = (Boolean) event.getNewValue();
            if (isNominated) {
                onPairSucceeded(pair);
            }
        }

        // SUCCESS PAIR != nominated
//        if (IceMediaStream.PROPERTY_PAIR_STATE_CHANGED.equals(event.getPropertyName())) {
//            CandidatePair pair = (CandidatePair) event.getSource();
//            CandidatePairState newState = (CandidatePairState) event.getNewValue();
//
//            if (CandidatePairState.SUCCEEDED.equals(newState)) {
//                ScheduledFuture<?> future = pendingPairs.remove(pair);
//                if (future != null) {
//                    future.cancel(false);
//                }
//                onPairSucceeded(pair);
//            }
//        }
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
        log.info("✅ Успех: Пара достигла SUCCEEDED: {}", pair);
        future.complete(true);
    }

    private void onPairTimeout(CandidatePair pair) {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.info("❌ Таймаут: Пара не достигла SUCCEEDED за {} мс: {}", timeoutMs, pair);
        future.complete(true);
    }

    private void agentConnectionFailed() {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.warn("❌ Agent не смог наладить соединение");
        future.complete(false);
    }

    private void agentConnectionSuccess() {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.warn("✅ Agent смог наладить соединение");
        future.complete(true);
    }

    public void shutdown() {
        scheduler.shutdown();
        pendingPairs.values().forEach(f -> f.cancel(true));
        pendingPairs.clear();
    }
}
