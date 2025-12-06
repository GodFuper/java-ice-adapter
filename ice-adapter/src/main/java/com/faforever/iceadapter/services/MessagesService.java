package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.Peer;

public interface MessagesService {

    void sendPacketToPeer(Peer peer, byte[] data, int offset, int length);

    void sendPacketToFA(Peer peer, byte[] data, int offset, int length);
}
