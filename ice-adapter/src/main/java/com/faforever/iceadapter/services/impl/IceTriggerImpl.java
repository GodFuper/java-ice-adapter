package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.IceTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceTriggerImpl implements IceTrigger {

    private final IceAsync iceAsync;
    private final ConnectService connectService;

    @Override
    public void onChangeIceState(Peer peer, IceState oldState, IceState newState) {
        iceAsync.runAsync(peer, () -> {
            log.info("Ice state changed from {} to {}", oldState, newState);
            connectService.onChangeIceState(peer, oldState, newState);
        });
    }
}
