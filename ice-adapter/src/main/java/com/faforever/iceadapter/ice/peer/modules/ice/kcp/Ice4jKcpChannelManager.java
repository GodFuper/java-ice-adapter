package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.channel.socket.DatagramPacket;
import kcp.IChannelManager;
import kcp.Ukcp;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Ice4jKcpChannelManager implements IChannelManager {
    private final int index = 0;
    private final Map<Integer, Ukcp> channels = new ConcurrentHashMap<>();

    @Override
    public Ukcp get(DatagramPacket msg) {
        return channels.get(index);
    }

    @Override
    public void add(SocketAddress socketAddress, Ukcp ukcp, DatagramPacket msg) {
        channels.put(index, ukcp);
    }

    @Override
    public void del(Ukcp ukcp) {
    }

    @Override
    public Collection<Ukcp> getAll() {
        return channels.values();
    }
}
