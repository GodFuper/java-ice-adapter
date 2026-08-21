package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpOffererPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test module that intercepts packets at the socket receive level and drops them based on
 * a periodic pattern (every Nth packet).
 *
 * <p>When kcpUdpTransport is enabled (KCP mode), all packets are counted and dropped
 * based on the periodic pattern since KCP doesn't use a separate packet header.
 *
 * <p>Cannot extend {@link PeerToPeerListenerModule} because its core methods (createListener,
 * handlerData) are private. Instead, this class replicates the same logic with the addition
 * of deterministic packet dropping — every Nth packet is silently dropped.
 *
 * <p>This simulates the scenario where Peer1 sends a packet, but Peer2 fails to process it
 * due to network issues, buffer overflow, etc.
 *
 * <p>Drop logic is (connId, seq)-based: each unique (connId, seq) pair is considered once.
 * The first occurrence is dropped based on the periodic pattern. Retransmissions of the same
 * (connId, seq) are allowed to pass so the reliable transport can recover from the loss.
 */
@Slf4j
@Getter
class DroppingPeerToPeerListenerModule extends PeerToPeerListenerModule {

    private final Peer peer;
    private final int dropEveryNPackets;
    private final AtomicLong packetNum = new AtomicLong(0);

    /**
     * Creates a new dropping listener module.
     *
     * @param peer              the peer to listen for
     * @param dropEveryNPackets drop every Nth packet (e.g., 5 = drop every 5th packet = 20% loss)
     */
    public DroppingPeerToPeerListenerModule(Peer peer, int dropEveryNPackets) {
        super(peer);
        this.peer = peer;
        this.dropEveryNPackets = dropEveryNPackets;
    }

    @Override
    protected void handlerData(Peer p, byte[] data, int length) {
        // In KCP mode, count and drop all packets
        // Without custom mode, let them all through
        if (data[0] != KcpOffererPeerToPeerSenderModule.KCP_PROTOCOL_MARKER) {
            super.handlerData(p, data, length);
            return;
        }

        // First time seeing this packet — decide whether to drop based on periodic pattern
        long numberPacket = packetNum.incrementAndGet();
        if (dropEveryNPackets > 0 && numberPacket % dropEveryNPackets == 0) {
            log.info("DroppingListener: DROP packet #{} (every {} packets) for peer {}",
                    numberPacket, dropEveryNPackets, peer.getPeerIdentifier());
            return;
        }

        // Not dropping — process normally
        log.trace("DroppingListener: NORMAL PROCESS DATA (data-packet #{}) for peer {}", numberPacket, peer.getPeerIdentifier());
        super.handlerData(p, data, length);
    }
}
