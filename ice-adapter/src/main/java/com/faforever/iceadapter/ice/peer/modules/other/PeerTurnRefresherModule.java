package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.turn.ModifyRelayedCandidate;
import com.faforever.iceadapter.ice.turn.ModifyTurnCandidateHarvest;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvest;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class PeerTurnRefresherModule implements ModuleBase, PeerEventListener {
    private static final int REFRESH_INTERVAL = 2;
    private static final String LOCK_REFRESHER_MODULE = "PeerTurnRefresherModule";

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    private final Peer peer;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_REFRESHER_MODULE), () -> {
            if (peer.isClosing()) {
                return;
            }
            if (isRunning()) {
                return;
            }

            log.debug("Starting refresher relay for peer");
            scheduledFuture = scheduledExecutorService.scheduleAtFixedRate(
                    this::refresher, 1, REFRESH_INTERVAL, TimeUnit.MINUTES);
        });
    }

    @Override
    public Boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isDone();
    }

    private String getThreadName() {
        return "refresherModule-%s".formatted(peer.getPeerIdentifier());
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_REFRESHER_MODULE), () -> {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
                scheduledFuture = null;
            }
        });
    }

    private void refresher() {
        CandidatePair pair = peer.getSelectedPair();
        if (pair == null) {
            return;
        }
        Thread.currentThread().setName(getThreadName());
        LocalCandidate candidate = pair.getLocalCandidate();
        if (candidate instanceof ModifyRelayedCandidate relayedCandidate) {

            TurnCandidateHarvest harvest = relayedCandidate.getTurnCandidateHarvest();

            if (harvest instanceof ModifyTurnCandidateHarvest modifyHarvest) {
                try {
                    modifyHarvest.sendRefresh();
                    log.info("Sent turn refresh request");
                } catch (Exception e) {
                    log.error("Could not send turn refresh request!", e);
                }
            }
        }
    }
}
