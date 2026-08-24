package com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework;

import lombok.RequiredArgsConstructor;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
@RequiredArgsConstructor
public class MyKcpMetric {

    private final MyKcp kcp;

    private int maxSegXmit;

    public int srtt() {
        return kcp.getSrtt();
    }

    public int rttvar() {
        return kcp.getRttvar();
    }

    public int rto() {
        return kcp.getRto();
    }

    public long sndNxt() {
        return kcp.getSndNxt();
    }

    public long sndUna() {
        return kcp.getSndUna();
    }

    public long rcvNxt() {
        return kcp.getRcvNxt();
    }

    public int cwnd() {
        return kcp.getCwnd();
    }

    public int xmit() {
        return kcp.getXmit();
    }

    public int maxSegXmit() {
        return maxSegXmit;
    }

    public void maxSegXmit(int maxSegXmit) {
        this.maxSegXmit = maxSegXmit;
    }

    @Override
    public String toString() {
        return "MyKcpMetric(" +
                "kcp=" + kcp +
                ", srtt=" + srtt() +
                ", rttvar=" + rttvar() +
                ", rto=" + rto() +
                ", sndNxt=" + sndNxt() +
                ", sndUna=" + sndUna() +
                ", rcvNxt=" + rcvNxt() +
                ", cwnd=" + cwnd() +
                ", xmit=" + xmit() +
                ", maxSegXmit=" + maxSegXmit +
                ')';
    }

}
