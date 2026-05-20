package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.dto.command.info.InfoRelayStatusCommand;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class InfoStatusModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        if (peer instanceof MainPeer mainPeer) {
            peer.sendCommand(new InfoRelayStatusCommand(mainPeer.isServerMode()), true);
        }
    }

}
