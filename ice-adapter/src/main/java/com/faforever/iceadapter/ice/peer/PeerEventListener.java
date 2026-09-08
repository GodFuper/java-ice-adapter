package com.faforever.iceadapter.ice.peer;
import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

public interface PeerEventListener {
    default void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
    }

    default void onConnectingChange(Peer peer, boolean connecting) {
    }

    default void onAgentChange(Peer peer, Agent agent) {
    }

    default void onIceMediaStreamChange(Peer peer, IceMediaStream stream) {
    }

    default void onIceComponentChange(Peer peer, Component component) {
    }

    default void onCombinationChange(Peer peer, AllowCombination combination) {
    }

    default void onRelayPeerChange(Peer peer, Peer relay) {
    }

    default void onPeerSendModeChange(Peer peer, PeerSendMode oldMode, PeerSendMode newMode) {
    }

    default void onAddServerPeer(Peer peer, ServerPeer serverPeer) {
    }

    default void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
    }

    default void onChangeEcho(Peer peer, Long lastEcho, long echo) {
    }

    default void onHandleData(Peer peer, byte[] data) {
    }

    default void onHandleCommand(Peer peer, CommandBase command) {
    }

    default void onSendToPeer(Peer peer, byte[] data) {
    }

    default void onSendCommand(Peer peer, CommandBase command, boolean force) {
    }

    default void onConnectionLost(Peer peer, boolean clearIceState) {
    }

    default void onClose(Peer peer, boolean hasClosed) {
    }

    default void onSendToRpc(Peer peer, CandidatesMessage message) {
    }

    default void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
    }
}
