package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;

public interface IceTrigger {

    void onChangeIceState(Peer peer, IceState oldState, IceState newState);
}
