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
import io.jpower.kcp.netty.Kcp;
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
public class KcpOffererPeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final int PERIOD_GET_STATISTIC = 100;
    private static final String LOCK_TRANSPORT = "KcpTransport";
    public static final byte KCP_PROTOCOL_MARKER = 'u';

    private final Peer peer;
    private volatile Component component;
    private volatile KcpAdapter kcpAdapter;
    private volatile byte conv;
    private Lock lockTransport;

    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> statisticTask;

    @Override
    public void init() {
        // Only activate for offerer peers (localOffer = true)
        if (peer.isLocalOffer()) {
            peer.addEventListener(this);
        } else {
            log.debug("KcpOffererPeerToPeerSenderModule skipped for answerer peer {}",
                    peer.getPeerIdentifier());
        }
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
        KcpAdapter adapter = this.kcpAdapter;
        if (!isEnabled() || component == null || adapter == null) {
            return;
        }
        checkStateSafe();
        adapter.send(data);
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        if (!peer.isSupportCommand() && !force) {
            return;
        }
        KcpAdapter adapter = this.kcpAdapter;
        if (!isEnabled() || component == null || adapter == null) {
            return;
        }
        checkStateSafe();
        adapter.send(command.bytes());
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        int len = data.length;
        if (len < 2 || data[0] != KCP_PROTOCOL_MARKER || data[1] != conv) {
            return;
        }
        // KCP processes all non-STUN, non-command packets automatically
        // KcpAdapter itself calls peer.handleData() via receive() loop
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null && adapter.isRunning()) {
            checkStateSafe();
            adapter.onIncomingPacket(data, 2, len - 2);
        }
    }

    private void checkStateSafe() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null && adapter.getState() == -1) {
            LockUtil.tryExecuteWithLock(lockTransport, () -> {
                KcpAdapter lockedAdapter = this.kcpAdapter;
                if (lockedAdapter != null && lockedAdapter.getState() == -1) {
                    createAdapterLocked();
                }
            });
        }
    }

    @Override
    public void start() {
        if (!isEnabled()) {
            return;
        }

        createAdapter();
    }

    @Override
    public void stop() {
        stopAdapter();
    }

    private void createAdapter() {
        LockUtil.executeWithLock(lockTransport, this::createAdapterLocked);
    }

    private void createAdapterLocked() {
        if (component != null) {
            conv = (byte) random.nextInt(256);
            startAdapter(conv);
            log.info("KCP transport created for offerer peer {} with conv={}", peer.getPeerIdentifier(), conv);
        }
    }

    private void startAdapter(byte convForCreate) {
        if (component != null) {
            stopAdapterLocked();

            PeerKcpOutput kcpOutput = new PeerKcpOutput(peer, convForCreate);
            Consumer<byte[]> handle = peer::handleData;
            kcpAdapter = new KcpAdapter(convForCreate, peer.getPeerIdentifier(), kcpOutput, handle);
            kcpAdapter.start();

            if (statisticTask != null && !statisticTask.isDone()) {
                statisticTask.cancel(false);
            }
            statisticTask = scheduler.scheduleAtFixedRate(this::doStatistic, 0, PERIOD_GET_STATISTIC, TimeUnit.MILLISECONDS);
        }
    }

    private void checkAndRecreateIfDead() {
        if (kcpAdapter != null) {
            int state = kcpAdapter.getState();
            if (state == -1) {
                log.info("KCP adapter state is -1 (dead-link) for offerer peer {}, recreating", peer.getPeerIdentifier());
                createAdapter();
            }
        } else {
            createAdapter();
        }
    }

    private void stopAdapterLocked() {
        if (kcpAdapter != null) {
            kcpAdapter.stop();
            kcpAdapter = null;
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
            Kcp kcp = adapter.getKcp();
            if (kcp != null) {
                statistics.update(kcp, adapter.getConv());
                statistics.updateNextUpdate(adapter.getNextUpdateTimestamp());
                statistics.updateBytes(adapter.getBytesSent(), adapter.getBytesReceived());
            } else {
                statistics.reset();
            }
        }
    }
}
