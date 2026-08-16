package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.ReliableUdpTransport;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.TransportConnectionStats;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader;
import com.faforever.iceadapter.util.LockUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.util.concurrent.locks.Lock;

import static com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability.RELIABLE;

/**
 * Custom Reliable UDP sender module.
 * <p>
 * Implements ModuleBase and PeerEventListener to intercept send operations
 * and route them through the Custom Reliable UDP transport instead of
 * the default PeerToPeerSenderModule.
 * <p>
 * By default disabled. Enable via --custom-reliable-udp command line option
 * or by calling enable().
 * <p>
 * When enabled, replaces RelayPeerToPeerSenderModule functionality.
 */
@Getter
@Slf4j
@RequiredArgsConstructor
public class CustomUdpTransportSenderModule implements ModuleBase, PeerEventListener {

    private static final String LOCK_TRANSPORT = "CustomUdpTransport";

    private final Peer peer;
    private Component component;
    private ReliableUdpTransport transport;

    private Lock lockTransport;

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
    public void onCustomUdpTransportChange(Peer peer, boolean enabled) {
        refresh();
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        Component component = this.component;
        ReliableUdpTransport transport = this.transport;
        if (!isEnabled() || component == null || transport == null) {
            return;
        }

        transport.send(0, RELIABLE, data);
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        if (!peer.isSupportCommand() && !force) {
            return;
        }
        Component component = this.component;
        ReliableUdpTransport transport = this.transport;
        if (!isEnabled() || component == null || transport == null) {
            return;
        }

        byte[] data = command.bytes();
        transport.send(0, RELIABLE, data);
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (data[0] != PacketHeader.MAGIC || !isEnabled()) {
            return;
        }

        ReliableUdpTransport transport = this.transport;
        if (transport != null && transport.isRunning()) {
            transport.onIncomingPacket(data);
        } else {
            log.debug("CustomUdpTransportSenderModule: transport null or not running for peer {}", peer.getPeerIdentifier());
        }
    }

    @Override
    public void start() {
        createTransport();
    }

    @Override
    public void stop() {
        stopTransport();
    }

    private void createTransport() {
        LockUtil.executeWithLock(lockTransport, () -> {
            // Initialize transport if component is available
            if (component != null && transport == null) {
                transport = new ReliableUdpTransport(peer, component, peer.getRemoteId());
                transport.start();
            }
        });
    }

    private void stopTransport() {
        LockUtil.executeWithLock(lockTransport, () -> {
            if (transport != null) {
                log.info("CustomRelaySenderModule stoped for peer {}", peer.getPeerIdentifier());
                transport.stop();
                transport = null;
            }
        });
    }

    @Override
    public Boolean isEnabled() {
        return peer.isCustomUdpTransport();
    }

    @Override
    public void refresh() {
        if (isEnabled()) {
            createTransport();
        } else {
            stopTransport();
        }
    }

    /**
     * Get transport statistics.
     */
    public TransportConnectionStats getStats() {
        return transport != null ? transport.getStats() : null;
    }
}
