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

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * KCP peer-to-peer sender module.
 * <p>
 * Replaces CustomUdpTransportSenderModule. Intercepts send operations
 * and routes them through KCP protocol instead of plain UDP or the
 * old ReliableUdpTransport.
 * <p>
 * Enabled via --kcp-udp command line option or by calling enable().
 * When enabled, replaces PeerToPeerSenderModule/RelayPeerToPeerSenderModule/AutoRelayPeerToPeerSenderModule functionality.
 */
@Slf4j
@RequiredArgsConstructor
public class KcpPeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final int PERIOD_GET_STATISTIC = 100;
    private static final String LOCK_TRANSPORT = "KcpTransport";
    public static final byte KCP_PROTOCOL_MARKER = 'u';
    private final Peer peer;
    private Component component;
    private KcpAdapter kcpAdapter;
    private Lock lockTransport;
    private PeerKcpOutput kcpOutput;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> statisticTask;

    @Override
    public void init() {
        peer.addEventListener(this);
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
        if (data[0] != KCP_PROTOCOL_MARKER) {
            return;
        }
        // KCP processes all non-STUN, non-command packets automatically
        // KcpAdapter itself calls peer.handleData() via receive() loop
        KcpAdapter adapter = this.kcpAdapter;
        if (adapter != null && adapter.isRunning()) {
            adapter.onIncomingPacket(data, 1, data.length - 1);
        }
    }

    @Override
    public void start() {
        createAdapter();
    }

    @Override
    public void stop() {
        stopAdapter();
    }

    private void createAdapter() {
        LockUtil.executeWithLock(lockTransport, () -> {
            if (component != null && kcpAdapter == null && isEnabled()) {
                kcpOutput = new PeerKcpOutput(peer);
                Consumer<byte[]> handle = peer::handleData;
                int conv = peer.isLocalOffer() ? peer.getRemoteId() : peer.getFromId();
                kcpAdapter = new KcpAdapter(conv, peer.getPeerIdentifier(), kcpOutput, handle);
                kcpAdapter.start();
                log.info("KCP transport started for peer {}", peer.getPeerIdentifier());

                if (statisticTask != null && !statisticTask.isDone()) {
                    statisticTask.cancel(false);
                }
                statisticTask = scheduler.scheduleAtFixedRate(this::doStatistic, 0, PERIOD_GET_STATISTIC, TimeUnit.MILLISECONDS);
            }
        });
    }

    private void stopAdapter() {
        LockUtil.executeWithLock(lockTransport, () -> {
            if (kcpAdapter != null) {
                kcpAdapter.stop();
                kcpAdapter = null;
                kcpOutput.getKcp().ifPresent(kcp -> {
                    kcp.setState(-1);
                    kcp.release();
                });
                kcpOutput = null;
                log.info("KCP transport stopped for peer {}", peer.getPeerIdentifier());
                if (statisticTask != null) {
                    statisticTask.cancel(false);
                }
            }
        });
    }

    @Override
    public Boolean isEnabled() {
        return peer.isKcpTransportEnabled();
    }

    @Override
    public void refresh() {
        if (isEnabled()) {
            createAdapter();
        } else {
            stopAdapter();
        }
        updateStatistic(null);
    }

    private void doStatistic() {
        PeerKcpOutput kcpOutput = this.kcpOutput;
        if (kcpOutput == null) {
            return;
        }
        Optional<Kcp> kcpOptional = kcpOutput.getKcp();

        kcpOptional.ifPresent(this::updateStatistic);
    }

    private void updateStatistic(Kcp kcp) {
        KcpStatistics statistics = peer.getKcpStatistics();
        KcpAdapter adapter = this.kcpAdapter;
        if (statistics != null) {
            if (kcp != null) {
                statistics.update(kcp);
                if (adapter != null) {
                    statistics.updateNextUpdate(adapter.getNextUpdateTimestamp());
                }
            } else {
                statistics.reset();
            }
        }
    }
}
