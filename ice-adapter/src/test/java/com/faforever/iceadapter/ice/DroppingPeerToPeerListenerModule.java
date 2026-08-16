package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeaderCodec;
import kotlin.Pair;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test module that intercepts packets at the socket receive level and drops them based on
 * a periodic pattern (every Nth packet).
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
    private final AtomicLong firstTimeSeqCount = new AtomicLong(0);
    // Track (connId, seq) pairs that have been seen (to avoid counting retransmissions)
    // Key format: (connId << 32) | seq
    private final ConcurrentHashMap<Pair<Integer, Integer>, Boolean> seenSeqs = new ConcurrentHashMap<>();
    private final Set<Integer> lostSequence = new LinkedHashSet<>();
    private final Set<Integer> successSequence = new LinkedHashSet<>();
    private final Set<Integer> retransmissionSequence = new LinkedHashSet<>();

    private static Pair<Integer, Integer> packKey(int connId, int seq) {
        return new Pair<>(connId, seq);
    }

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
        // Only count and drop packets with the custom UDP header magic byte
        // This excludes STUN packets and other control packets that shouldn't affect drop ratio
        if (data.length < PacketHeader.HEADER_SIZE || data[0] != PacketHeader.MAGIC) {
            super.handlerData(p, data, length);
            return;
        }

        PacketHeader header = PacketHeaderCodec.decode(ByteBuffer.wrap(data));

        // ONLY count and drop DATA packets. NACK and KEEP_ALIVE must pass through
        // unaffected so the reliable transport protocol can function correctly.
        // If we counted NACK/KEEP_ALIVE in the drop ratio, it would shift the
        // expected seq numbers for DATA drops.
        if (header.getType() != PacketHeader.PacketType.DATA) {
            super.handlerData(p, data, length);
            return;
        }

        int connId = header.getConnId();
        int seq = header.getSeq();
        var key = packKey(connId, seq);

        // Check if this is a retransmission (already seen this seq)
        Boolean wasSeen = seenSeqs.putIfAbsent(key, Boolean.TRUE);
        if (wasSeen != null) {
            retransmissionSequence.add(seq);
            // Retransmission - allow it through so reliable transport can recover
            log.info("DroppingListener: ALLOW retransmission connId={} seq={} for peer {}",
                    connId, seq, peer.getPeerIdentifier());
            super.handlerData(p, data, length);
            return;
        }

        // First time seeing this DATA packet — decide whether to drop based on periodic pattern
        long count = firstTimeSeqCount.incrementAndGet();
        if (dropEveryNPackets > 0 && count % dropEveryNPackets == 0) {
            log.info("DroppingListener: DROP DATA connId={} seq={} (data-packet #{} every {} packets) for peer {}",
                    connId, seq, count, dropEveryNPackets, peer.getPeerIdentifier());
            // Debug: print first few drops
            if (count <= 200) {
                log.info("DroppingListener: === DROP TRACE count={} seq={}", count, seq);
            }
            lostSequence.add(seq);
            return;
        }

        // Not dropping — process normally
        log.trace("DroppingListener: PROCESS DATA connId={} seq={} (data-packet #{}) for peer {}",
                connId, seq, count, peer.getPeerIdentifier());
        successSequence.add(seq);
        super.handlerData(p, data, length);
    }
}
