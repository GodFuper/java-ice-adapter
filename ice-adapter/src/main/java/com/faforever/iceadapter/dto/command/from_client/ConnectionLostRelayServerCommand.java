package com.faforever.iceadapter.dto.command.from_client;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.from_server.ServerPeerStatusCommand;
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
public class ConnectionLostRelayServerCommand extends CommandBase {
    private int remoteId;
    private boolean clearIceState;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            removeServer(mainPeer);
        }
    }

    public void removeServer(MainPeer peer) {
        ServerPeer server = peer.getRelays().get(remoteId);
        if (server == null) {
            peer.sendCommand(new ServerPeerStatusCommand(remoteId, false, null));
            return;
        }

        server.lostConnect(clearIceState);
        peer.sendCommand(new ServerPeerStatusCommand(remoteId, true, server.getIceState()));
    }

}
