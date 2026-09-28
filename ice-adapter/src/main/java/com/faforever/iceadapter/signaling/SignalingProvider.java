package com.faforever.iceadapter.signaling;

import com.faforever.iceadapter.ice.CandidatesMessage;

public interface SignalingProvider {

    void init();

    void sendSignalingMessage(CandidatesMessage message);

    void close();

    default void onPeerDisconnected(int peerId) {}
}
