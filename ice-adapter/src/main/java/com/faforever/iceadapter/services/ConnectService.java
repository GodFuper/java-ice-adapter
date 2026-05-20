package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;

public interface ConnectService {

    void onChangeIceState(Peer peer, IceState oldState, IceState iceState);

    void onConnectionLost(Peer peer, boolean clearIceState);

    void onMessageFromRPC(Peer peer, CandidatesMessage message);
}
