package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.buffer.ByteBuf;
import kcp.ChannelConfig;
import kcp.KcpOutput;
import kcp.Ukcp;
import threadPool.IMessageExecutor;

import java.util.function.Consumer;

public class Ice4jUkcp extends Ukcp {

    public Ice4jUkcp(KcpOutput output,
                     Consumer<byte[]> handleData,
                     IMessageExecutor executor,
                     ChannelConfig channelConfig,
                     Ice4jKcpChannelManager manager) {
        super(output,
                new Ice4jKcpListener(handleData),
                executor,
                channelConfig,
                manager);
    }

    public void receivedPacket(ByteBuf packet) {
        read(packet);
    }

    public long updateKcp(long current) {
        return update(current);
    }

    public boolean canReceive() {
        return canRecv();
    }

    public ByteBuf mergeReceived() {
        return mergeReceive();
    }

}
