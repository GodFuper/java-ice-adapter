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

@Slf4j
@RequiredArgsConstructor
public class KcpReceiverModule implements ModuleBase, PeerEventListener {
    private static final int PERIOD_GET_STATISTIC = 100;
    private static final String LOCK_TRANSPORT = "KcpTransport";

    private final Peer peer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> statisticTask;
    private volatile Component component;
    private volatile KcpAdapter kcpAdapter;
    private byte channel;
    private Lock lockTransport;

    @Override
    public void init() {
        peer.addEventListener(this);
        channel = peer.isLocalOffer() ? (byte) 1 : (byte) 0;
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
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
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
        KcpAdapter adapter = this.kcpAdapter;

        byte channel = data[1];
        boolean convEquals = adapter != null && (byte) adapter.getConv() == channel;

        if (!convEquals) {
            adapter = LockUtil.executeWithLock(lockTransport, () -> createAdapter(channel));
        }

        boolean stateIsNotActive = adapter != null && adapter.getState() == -1;

        if (stateIsNotActive) {
            adapter = LockUtil.executeWithLock(lockTransport, this::sendNotifyAboutDeadKcp);
        }

        if (adapter != null && adapter.isRunning()) {
            int len = data.length;
            adapter.onIncomingPacket(data, 2, len - 2);
        }
    }

    private KcpAdapter sendNotifyAboutDeadKcp() {
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter == null) {
            return createAdapter(channel);
        }
        if (adapter.getState() != -1) {
            return adapter;
        }

        Component component = this.component;
        if (component == null) {
            log.error("Component is null. Kcp is DEAD. Cant send fail command. Peer {}", peer.getPeerIdentifier());
            return adapter;
        }

        InfoKcpDeadStateCommand command = new InfoKcpDeadStateCommand(channel);
        byte[] bytes = command.bytes();
        try {
            component.send(bytes, 0, bytes.length);
            log.info("Send DeadState with channel {}. Peer {}", channel, peer.getPeerIdentifier());
        } catch (IOException e) {
            log.error("Failed to send the command about the dead state KCP. Peer {}", peer.getPeerIdentifier());
        }

        return createAdapter(channel);
    }

    private KcpAdapter createAdapter(byte channel) {
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
        log.info("KCP adapter created for answerer peer {} with channel={}", peer.getPeerIdentifier(), channel);
        return kcpAdapter;
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
//        updateStatistic(adapter);
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
