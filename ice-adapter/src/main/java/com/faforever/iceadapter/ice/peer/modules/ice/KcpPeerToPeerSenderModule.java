package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.KcpAdapter;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.KcpStatistics;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.PeerKcpOutput;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * KCP peer-to-peer sender module for the offerer (localOffer = true).
 * <p>
 * This module:
 * <ul>
 *   <li>Generates a random conv byte on creation</li>
 *   <li>Creates KcpAdapter with the generated conv immediately on init</li>
 *   <li>Recreates conv when KcpAdapter state becomes -1 (dead-link)</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class KcpPeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final int PERIOD_GET_STATISTIC = 100;
    private static final String LOCK_TRANSPORT = "KcpTransport";
    public static final byte KCP_PROTOCOL_MARKER = 'u';

    private final Peer peer;
    private volatile Component component;
    private volatile KcpAdapter kcpAdapter;
    private volatile byte channel;
    private Lock lockTransport;

    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> statisticTask;

    @Override
    public void init() {
        peer.addEventListener(this);
        channel = peer.isLocalOffer() ? (byte) 0 : (byte) 1;
        lockTransport = peer.getLock(LOCK_TRANSPORT);
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        this.component = component;
        refresh();
    }

    @Override
    public void onPeerSendModeChange(Peer peer, PeerSendMode oldMode, PeerSendMode newMode) {
        refresh();
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        KcpAdapter adapter = checkStateSafe();
        if (!isEnabled() || adapter == null) {
            return;
        }
        adapter.send(data);
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        if (!peer.isSupportCommand() && !force) {
            return;
        }
        KcpAdapter adapter = checkStateSafe();
        if (!isEnabled() || adapter == null) {
            return;
        }
        adapter.send(command.bytes());
    }

    private boolean isDataKcp(byte[] data) {
        int len = data.length;
        return len > 2
                && data[0] == KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER
                && data[1] == channel;
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (!isDataKcp(data)) {
            return;
        }
        // KCP processes all non-STUN, non-command packets automatically
        // KcpAdapter itself calls peer.handleData() via receive() loop
        KcpAdapter adapter = checkStateSafe();
        if (adapter != null) {
            int len = data.length;
            adapter.onIncomingPacket(data, 2, len - 2);
        }
    }

    private KcpAdapter checkStateSafe() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null && adapter.getState() == -1) {
            adapter = LockUtil.executeWithLock(lockTransport, () -> {
                KcpAdapter lockedAdapter = this.kcpAdapter;
                if (lockedAdapter != null && lockedAdapter.getState() == -1) {
                    return createAdapterLocked();
                }
                return lockedAdapter;
            });
        }
        return adapter;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        stopAdapter();
    }

    private void createAdapter() {
        LockUtil.executeWithLock(lockTransport, this::createAdapterLocked);
    }

    private KcpAdapter createAdapterLocked() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null) {
            if (adapter.getState() != -1) {
                return adapter;
            }
            log.info("KCP transport have State = {}, stopped. peer {}", adapter.getState(), peer.getPeerIdentifier());
            adapter.stop();
        }

        PeerKcpOutput kcpOutput = new PeerKcpOutput(peer, channel);
        Consumer<byte[]> handle = peer::handleData;
        KcpAdapter kcpAdapter = new KcpAdapter(channel, peer.getPeerIdentifier(), kcpOutput, handle);
        kcpAdapter.start();
        this.kcpAdapter = kcpAdapter;

        if (statisticTask != null && !statisticTask.isDone()) {
            statisticTask.cancel(false);
        }
        statisticTask = scheduler.scheduleAtFixedRate(this::doStatistic, 0, PERIOD_GET_STATISTIC, TimeUnit.MILLISECONDS);
        log.info("KCP transport created for offerer peer {} with channel={}", peer.getPeerIdentifier(), channel);
        return kcpAdapter;
    }

    private void checkAndRecreateIfDead() {
        if (component == null) {
            stopAdapter();
            return;
        }

        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null) {
            int state = adapter.getState();
            if (state == -1) {
                log.info("KCP adapter state is -1 (dead-link) for offerer peer {}, recreating", peer.getPeerIdentifier());
                createAdapter();
            }
        } else {
            createAdapter();
        }
    }

    private void stopAdapterLocked() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null) {
            adapter.stop();
            this.kcpAdapter = null;
            log.info("KCP transport stopped for offerer peer {}", peer.getPeerIdentifier());
            if (statisticTask != null) {
                statisticTask.cancel(false);
            }
        }
    }

    private void stopAdapter() {
        LockUtil.executeWithLock(lockTransport, this::stopAdapterLocked);
    }

    @Override
    public Boolean isEnabled() {
        return peer.isKcpTransportEnabled();
    }

    @Override
    public void refresh() {
        checkAndRecreateIfDead();
        updateStatistic(null);
    }

    public void restartKcp(byte channelRq) {
        if (channelRq != channel) {
            return;
        }
        createAdapter();
        updateStatistic(null);
    }

    private void doStatistic() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter == null) {
            return;
        }
        updateStatistic(adapter);
    }

    private void updateStatistic(KcpAdapter adapter) {
        KcpStatistics statistics = peer.getKcpStatistics();
        if (statistics != null && adapter != null) {
            statistics.update(adapter.getKcpInstance(), adapter.getConv());
            statistics.updateNextUpdate(adapter.getNextUpdateTimestamp());
            statistics.updateBytes(adapter.getBytesSent(), adapter.getBytesReceived());
        }
    }
}
