package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.IceState;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

public interface PeerEventListener {
    default void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
    }

    default void onAgentChange(Peer peer, Agent agent) {
    }

    default void onIceMediaStreamChange(Peer peer, IceMediaStream stream) {
    }

    default void onIceComponentChange(Peer peer, Component component) {
    }

    default void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
    }

    default void onChangeEcho(Peer peer, Long lastEcho, long echo) {
    }

    default void onSendToFaSocket(Peer peer, byte[] data, int offset, int length) {
    }

    default void onSendToPeer(Peer peer, byte[] data, int offset, int length) {
    }

    default void onConnectionLost(Peer peer) {
    }

    default void onClose(Peer peer, boolean hasClosed) {
    }
}
