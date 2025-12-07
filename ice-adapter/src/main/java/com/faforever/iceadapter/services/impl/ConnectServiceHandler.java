package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.services.ConnectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ConnectServiceHandler implements ConnectService {
    private final ConnectService controlledConnectService;
    private final ConnectService notControlledConnectService;

    @Override
    public void onChangeIceState(Peer peer, IceState oldState, IceState iceState) {
        if (peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onChangeIceState(peer, oldState, iceState);
        } else {
            notControlledConnectService.onChangeIceState(peer, oldState, iceState);
        }
    }

    @Override
    public void onConnectionLost(Peer peer) {
        if (peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onConnectionLost(peer);
        } else {
            notControlledConnectService.onConnectionLost(peer);
        }
    }

    @Override
    public void onIceMessageReceived(Peer peer, CandidatesMessage message) {
        if (message == null || peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onIceMessageReceived(peer, message);
        } else {
            notControlledConnectService.onIceMessageReceived(peer, message);
        }
    }
}
