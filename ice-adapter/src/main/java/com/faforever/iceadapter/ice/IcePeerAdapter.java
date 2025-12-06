package com.faforever.iceadapter.ice;

public interface IcePeerAdapter {

    void sendPacketToPeer(Peer peer, byte[] data, int offset, int length);

    void onIceDataReceived(Peer peer, byte[] data, int offset, int length);

    void onConnectionLost(Peer peer);
}
