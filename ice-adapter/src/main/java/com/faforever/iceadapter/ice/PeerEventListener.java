package com.faforever.iceadapter.ice;

public interface PeerEventListener {
    default void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
    }

    ;

    default void onClose(Peer peer) {
    }

    ;
}
