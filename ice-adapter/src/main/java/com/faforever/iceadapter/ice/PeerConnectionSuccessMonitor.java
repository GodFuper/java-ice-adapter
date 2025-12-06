package com.faforever.iceadapter.ice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidatePairState;
import org.ice4j.ice.IceMediaStream;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@RequiredArgsConstructor
public class PeerConnectionSuccessMonitor implements PropertyChangeListener {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<CandidatePair, ScheduledFuture<?>> pendingPairs = new ConcurrentHashMap<>();

    private String name = "";
    private final long timeoutMs;
    private final Runnable onSuccess;
    private final Runnable onFailure;

    /**
     * Начинает отслеживание всех пар из указанного Component.
     */
    public void start(Peer peer) {
        if (peer == null) {
            log.error("Peer is null. Aborting.");
            onFailure.run();
            return;
        }
        IceMediaStream mediaStream = peer.getMediaStream();
        if (mediaStream == null) {
            log.error("Media stream is null. Aborting.");
            onFailure.run();
            return;
        }

        name = peer.getPeerIdentifier();

        mediaStream.addPairChangeListener(this);

        for (CandidatePair pair : mediaStream.getCheckList()) {
            scheduleTimeoutCheck(pair);
        }
    }

    /**
     * Обработка изменения свойства — например, состояние пары изменилось.
     */
    @Override
    public void propertyChange(PropertyChangeEvent event) {
        if (IceMediaStream.PROPERTY_PAIR_STATE_CHANGED.equals(event.getPropertyName())) {
            CandidatePair pair = (CandidatePair) event.getSource();
            CandidatePairState newState = (CandidatePairState) event.getNewValue();

            if (CandidatePairState.SUCCEEDED.equals(newState)) {
                ScheduledFuture<?> future = pendingPairs.remove(pair);
                if (future != null) {
                    future.cancel(false);
                }
                onPairSucceeded(pair);
            }
        }
    }

    /**
     * Запускает проверку на таймаут для указанной пары.
     */
    private void scheduleTimeoutCheck(CandidatePair pair) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (pendingPairs.remove(pair) != null) {
                onPairTimeout(pair);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        pendingPairs.put(pair, future);
    }

    /**
     * Вызывается при успешном завершении (SUCCEEDED)
     */
    private void onPairSucceeded(CandidatePair pair) {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.info("✅ Успех: Пара достигла SUCCEEDED: {}", pair);
        onSuccess.run();
    }

    /**
     * Вызывается при таймауте — пара не перешла в SUCCEEDED
     */
    private void onPairTimeout(CandidatePair pair) {
        String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(oldThreadName, name));
        log.info("❌ Таймаут: Пара не достигла SUCCEEDED за {} мс: {}", timeoutMs, pair);
        onFailure.run();
    }

    /**
     * Останавливает мониторинг.
     */
    public void shutdown() {
        scheduler.shutdown();
        pendingPairs.values().forEach(f -> f.cancel(true));
        pendingPairs.clear();
    }
}
