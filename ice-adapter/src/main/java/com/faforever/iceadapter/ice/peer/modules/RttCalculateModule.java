package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.google.common.primitives.Longs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static com.faforever.iceadapter.ice.peer.modules.PeerConnectivityCheckerModule.COMMAND_ECHO;

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
    public void onIceDataReceived(Peer peer, byte[] data, int offset, int length) {
        if (data.length == 0) {
            return;
        }

        if (data[0] == COMMAND_ECHO && length == 9) {
            if (peer.isLocalOffer()) {
                calculateRttWhenAgentControlling(data, length);
            } else {
                calculateRttWhenAgentNotControlling(data, length);
            }
        }
    }

    private void calculateRttWhenAgentNotControlling(byte[] data, int length) {
        long echo = Longs.fromByteArray(Arrays.copyOfRange(data, 1, length));
        long rttMs = System.currentTimeMillis() - echo;
        int rtt = 2 * (int) (rttMs);
        calculateRtt(rtt);
    }

    private void calculateRttWhenAgentControlling(byte[] data, int length) {
        long sentMs = Longs.fromByteArray(Arrays.copyOfRange(data, 1, length));
        long rttMs = System.currentTimeMillis() - sentMs;
        int rtt = (int) (rttMs);
        calculateRtt(rtt);
    }

    private void calculateRtt(int rtt) {
        float oldRtt = peer.getRtt();
        float calcRtt = oldRtt == 0 ? rtt : oldRtt * 0.8f + (float) rtt * 0.2f;
        peer.setRtt(calcRtt);
    }
}
