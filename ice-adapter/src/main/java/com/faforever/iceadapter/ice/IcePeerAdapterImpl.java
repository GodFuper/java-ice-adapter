package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.MessagesService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IcePeerAdapterImpl implements IcePeerAdapter {
    private final ConnectService connectService;
    private final MessagesService messagesService;

    @Override
    public void sendPacketToPeer(Peer peer, byte[] data, int offset, int length) {
        messagesService.sendPacketToPeer(peer, data, offset, length);
    }

    @Override
    public void onIceDataReceived(Peer peer, byte[] data, int offset, int length) {
        messagesService.sendPacketToFA(peer, data, offset, length);
    }

    @Override
    public void onConnectionLost(Peer peer) {
        connectService.onConnectionLost(peer);
    }
}
