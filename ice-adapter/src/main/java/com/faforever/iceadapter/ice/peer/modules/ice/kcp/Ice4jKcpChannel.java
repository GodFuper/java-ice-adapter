package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

@RequiredArgsConstructor
public final class Ice4jKcpChannel {

    private final Ice4jUkcp ukcp;

    public boolean send(ByteBuf data) {
        return ukcp.write(data);
    }

    public void receive(ByteBuf data) throws IOException {
        ukcp.receivedPacket(data);
    }

    public long update(long current) {
        return ukcp.updateKcp(current);
    }

    public boolean isReadable() {
        return ukcp.canReceive();
    }

    public ByteBuf receive() {
        return ukcp.mergeReceived();
    }

    public void close() {
        ukcp.close();
    }
}
