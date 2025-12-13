package com.faforever.iceadapter.ice.peer.modules.info;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RttCalculateModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        peer.setRtt(0.0f);
    }

    @Override
    public void stop() {
        peer.setRtt(0.0f);
    }

    @Override
    public void onChangeEcho(Peer peer, long echo) {
        if (peer.isLocalOffer()) {
            calculateRttWhenAgentControlling(echo);
        } else {
            calculateRttWhenAgentNotControlling(echo);
        }
    }

    private void calculateRttWhenAgentNotControlling(long echo) {
        long rttMs = System.currentTimeMillis() - echo;
        int rtt = 2 * (int) (rttMs);
        calculateRtt(rtt);
    }

    private void calculateRttWhenAgentControlling(long echo) {
        long rttMs = System.currentTimeMillis() - echo;
        int rtt = (int) (rttMs);
        calculateRtt(rtt);
    }

    private void calculateRtt(int rtt) {
        float oldRtt = peer.getRtt();
        float calcRtt = oldRtt == 0 ? rtt : oldRtt * 0.8f + (float) rtt * 0.2f;
        peer.setRtt(calcRtt);
    }
}
