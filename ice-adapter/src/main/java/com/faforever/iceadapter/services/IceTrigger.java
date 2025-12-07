package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceTrigger implements PeerEventListener {

    private final IceAsync iceAsync;
    private final ConnectService connectService;

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        iceAsync.runAsync(peer, () -> {
            connectService.onChangeIceState(peer, oldState, newState);
        });
    }
}
