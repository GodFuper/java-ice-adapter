package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test module that randomly drops packets based on a probability.
 * Used to simulate unreliable network conditions for KCP reliability testing.
 */
@Slf4j
class RandomDroppingPeerToPeerListenerModule extends PeerToPeerListenerModule {

    private final int dropChancePercent;
    private final AtomicLong packetsSeen = new AtomicLong(0);

    public RandomDroppingPeerToPeerListenerModule(Peer peer, int dropChancePercent) {
        super(peer);
        this.dropChancePercent = dropChancePercent;
    }

    @Override
    protected void handlerData(Peer p, byte[] data, int length) {
        // Only intercept KCP packets
        if (length <= 1 || data[0] != KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER) {
            super.handlerData(p, data, length);
            return;
        }

        if (dropChancePercent > 0
                && ThreadLocalRandom.current().nextInt(100) < dropChancePercent) {
            long num = packetsSeen.incrementAndGet();
            log.info("RandomDropping: DROP packet #{} from peer {} (chance: {}%)",
                    num, p.getPeerIdentifier(), dropChancePercent);
            return;
        }

        super.handlerData(p, data, length);
    }
}
