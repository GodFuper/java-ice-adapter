package com.faforever.iceadapter.ice.peer.modules.relay.manual;

import com.faforever.iceadapter.dto.RelayMessage;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.RpcMessageFromServerPeerCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerConnectingCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerStatusCommand;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RelayServerModule implements ModuleBase, PeerEventListener {
    public static final char COMMAND_SERVER = 's';
    public static final char COMMAND_CLIENT = 'p';
    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onHandleData(Peer r, byte[] data) {
        if (peer instanceof ServerPeer serverPeer) {
            fromPeerToClient(serverPeer, data);
        }
    }

    @Override
    public void onSendToRpc(Peer p, CandidatesMessage message) {
        if (peer instanceof ServerPeer serverPeer) {
            Peer from = serverPeer.getFrom();
            from.sendCommand(new RpcMessageFromServerPeerCommand(serverPeer.getRemoteId(), message));
        }
    }

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        if (peer instanceof ServerPeer serverPeer) {
            Peer from = serverPeer.getFrom();
            from.sendCommand(new ServerPeerStatusCommand(serverPeer.getRemoteId(), true, newState));
        }
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        if (peer instanceof ServerPeer serverPeer) {
            Peer from = serverPeer.getFrom();
            from.sendCommand(new ServerPeerConnectingCommand(serverPeer.getRemoteId(), connecting));
        }
    }

    /**
     * Client <- Peer <- Server (here) <- Peer
     */
    private void fromPeerToClient(ServerPeer serverPeer, byte[] peerData) {
        Peer from = serverPeer.getFrom();
        int targetId = serverPeer.getRemoteId();

        RelayMessage relayMessage = new RelayMessage(targetId, peerData);
        log.warn("(server) send relayMessage to {}. {}", from.getPeerIdentifier(), relayMessage);
        byte[] messageBytes = relayMessage.toBytes((byte) COMMAND_SERVER);

        from.sendToPeer(messageBytes);
    }

}
