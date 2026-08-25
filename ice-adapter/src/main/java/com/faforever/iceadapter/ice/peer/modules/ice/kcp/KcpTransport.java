package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

public interface KcpTransport {
    void send(byte[] data);

    void onReceive(byte[] data, int offset, int length);

    int getConv();

    void close();
}
