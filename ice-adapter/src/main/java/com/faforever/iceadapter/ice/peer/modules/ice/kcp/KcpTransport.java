package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpMetric;

public interface KcpTransport {
    void send(byte[] data);

    void onReceive(byte[] data, int offset, int length);

    int getConv();

    void close();

    default MyKcpMetric getMetric() {
        return null;
    }

    default long getBytesSent() {
        return 0;
    }

    default long getBytesReceived() {
        return 0;
    }
}
