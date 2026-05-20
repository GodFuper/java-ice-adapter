package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceTrigger implements PeerEventListener {
    private final IceAsync iceAsync;
    private final ConnectService connectService;
    private final RpcConnection rpcConnection;

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        if (isDontUseConnectService(peer) || peer.isDisableConnectService()) {
            return;
        }

        iceAsync.runAsync(false, "onIceStateChange", peer, () -> connectService.onChangeIceState(peer, oldState, newState));
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        iceAsync.runAsync(true, "onConnectionLost", peer, () -> connectService.onConnectionLost(peer, clearIceState));
    }

    @Override
    public void onAddServerPeer(Peer peer, ServerPeer serverPeer) {
        if (serverPeer == null) {
            return;
        }
        serverPeer.addEventListener(this);
    }

    @Override
    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        if (isDontUseConnectService(peer)) {
            return;
        }

        iceAsync.runAsync(true, "onIceMessageFromRPC", peer, () -> connectService.onMessageFromRPC(peer, message));
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        if (peer instanceof MainPeer main) {
            rpcConnection.onConnected(peer, connecting);
        }
    }

    @Override
    public void onSendToRpc(Peer peer, CandidatesMessage message) {
        if (peer instanceof MainPeer main) {
            rpcConnection.sendToRpc(message);
        }
    }

    private boolean isDontUseConnectService(Peer peer) {
        return peer instanceof MainPeer mainPeer && mainPeer.getRelayPeer() != null;
    }
}
