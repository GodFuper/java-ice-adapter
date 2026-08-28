package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test module that intercepts packets at the socket receive level and drops them based on
 * a periodic pattern (every Nth packet).
 *
 * <p>Extends {@link PeerToPeerListenerModule} and overrides {@code handlerData} to inject
 * deterministic packet dropping. When KCP mode is active (KCP_PROTOCOL_MARKER detected),
 * packets are counted and dropped based on the periodic pattern.
 *
 * <p>This simulates the scenario where Peer1 sends a packet, but Peer2 fails to process it
 * due to network issues, buffer overflow, etc.
 *
 * <p>Drop logic is based on a global packet counter — every Nth KCP packet is silently dropped.
 * Retransmissions of the same packet are also dropped (no retransmission tracking).
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
        if (data[0] != KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER) {
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
