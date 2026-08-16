package com.faforever.iceadapter.dto.command.relay.manual.from_client;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerStatusCommand;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class SetIceStateCommand extends CommandBase {
    private int remoteId;
    private IceState iceState;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            executePeer(mainPeer);
        }
    }

    private void executePeer(MainPeer peer) {
        ServerPeer server = peer.getRelays().get(remoteId);
        if (server == null) {
            peer.sendCommand(new ServerPeerStatusCommand(remoteId, false, null));
            return;
        }

        server.setIceState(iceState);
        peer.sendCommand(new ServerPeerStatusCommand(remoteId, true, server.getIceState()));
    }
}
