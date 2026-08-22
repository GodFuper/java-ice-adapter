package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.kcp.InfoKcpDeadStateCommand;
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

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * KCP peer-to-peer sender module for the answerer (localOffer = false).
 * <p>
 * This module:
 * <ul>
 *   <li>Receives KCP packets from the offerer containing conv byte</li>
 *   <li>Extracts conv from incoming packets (second byte after 'u' marker)</li>
 *   <li>Creates KcpAdapter with the extracted conv</li>
 *   <li>Forwards KCP data to the adapter for processing</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class KcpAnswererPeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final int PERIOD_GET_STATISTIC = 100;
    private static final String LOCK_TRANSPORT = "KcpTransport";

    private final Peer peer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> statisticTask;
    private volatile Component component;
    private volatile KcpAdapter kcpAdapter;
    private Lock lockTransport;

    @Override
    public void init() {
        // Only activate for answerer peers (localOffer = false)
        if (!peer.isLocalOffer()) {
            peer.addEventListener(this);
        } else {
            log.debug("KcpAnswererPeerToPeerSenderModule skipped for offerer peer {}",
                    peer.getPeerIdentifier());
        }
        lockTransport = peer.getLock(LOCK_TRANSPORT);
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        this.component = component;
    }

    @Override
    public void onPeerSendModeChange(Peer peer, PeerSendMode oldMode, PeerSendMode newMode) {
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        KcpAdapter adapter = this.kcpAdapter;
        if (!isEnabled() || component == null || adapter == null) {
            return;
        }
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
        adapter.send(command.bytes());
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        int len = data.length;
        if (len < 2 || data[0] != KcpOffererPeerToPeerSenderModule.KCP_PROTOCOL_MARKER) {
            return;
        }

        byte receivedConv = data[1];
        boolean kcpExist = kcpAdapter != null;
        boolean convNotEquals = kcpExist && (byte) kcpAdapter.getConv() != receivedConv;

        if (!kcpExist || convNotEquals) {
            LockUtil.executeWithLock(lockTransport, () -> {
                createAdapter(receivedConv);
            });
        }

        boolean stateIsNotActive = kcpExist && kcpAdapter.getState() == -1;
        if (stateIsNotActive) {
            LockUtil.executeWithLock(lockTransport, this::sendNotifyAboutNotActiveKcp);
            return;
        }

        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null && adapter.isRunning()) {
            adapter.onIncomingPacket(data, 2, len - 2);
        }
    }

    private void sendNotifyAboutNotActiveKcp() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter == null) {
            return;
        }
        Component component = this.component;
        if (component == null) {
            return;
        }

        byte conv = (byte) adapter.getConv();
        InfoKcpDeadStateCommand command = new InfoKcpDeadStateCommand(conv);
        byte[] bytes = command.bytes();
        try {
            component.send(bytes, 0, bytes.length);
        } catch (IOException e) {
            log.error("Failed to send the command about the dead state KCP. Peer {}", peer.getPeerIdentifier());
        }
    }

    private void createAdapter(byte conv) {
        if (component != null) {
            stopAdapterLocked();
            PeerKcpOutput kcpOutput = new PeerKcpOutput(peer, conv);
            Consumer<byte[]> handle = peer::handleData;
            kcpAdapter = new KcpAdapter(conv, peer.getPeerIdentifier(), kcpOutput, handle);
            kcpAdapter.start();
            if (statisticTask != null && !statisticTask.isDone()) {
                statisticTask.cancel(false);
            }
            statisticTask = scheduler.scheduleAtFixedRate(this::doStatistic, 0, PERIOD_GET_STATISTIC, TimeUnit.MILLISECONDS);
            log.info("KCP adapter created for answerer peer {} with conv={}", peer.getPeerIdentifier(), conv);
        }
    }

    private void stopAdapterLocked() {
        if (kcpAdapter != null) {
            kcpAdapter.stop();
            kcpAdapter = null;
            if (statisticTask != null) {
                statisticTask.cancel(false);
            }
            log.info("KCP adapter stopped for answerer peer {}", peer.getPeerIdentifier());
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
    public void start() {
        // Answerer waits for first packet from offerer
    }

    @Override
    public void stop() {
        stopAdapter();
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
