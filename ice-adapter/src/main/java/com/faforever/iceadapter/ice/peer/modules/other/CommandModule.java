package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class CommandModule implements ModuleBase, PeerEventListener {

    public static final char COMMAND_BASE = 'c';

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }


    @Override
    public void onHandleData(Peer p, byte[] data) {
        if (data[0] != COMMAND_BASE) {
            return;
        }

        executeCommand(data);
    }

    private void executeCommand(byte[] data) {
        CommandBase command = CommandBase.initCommand(data);

        if (command == null) {
            log.warn("Command is null from {}", peer.getPeerIdentifier());
            return;
        }

        try {
            log.info("Executing command {} from {}", command, peer.getPeerIdentifier());
            command.execute(peer);
        } catch (Exception e) {
            log.error("Error while executing command {} from {}", command, peer.getPeerIdentifier(), e);
        }
    }
}
