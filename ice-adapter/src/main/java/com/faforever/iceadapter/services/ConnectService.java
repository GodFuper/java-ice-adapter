package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;

public interface ConnectService {

    void onChangeIceState(Peer peer, IceState oldState, IceState iceState);

    void onConnectionLost(Peer peer);

    void onIceMessageReceived(Peer peer, CandidatesMessage message);
}
