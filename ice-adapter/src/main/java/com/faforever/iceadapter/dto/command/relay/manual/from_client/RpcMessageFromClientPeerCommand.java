package com.faforever.iceadapter.dto.command.relay.manual.from_client;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerStatusCommand;
import com.faforever.iceadapter.ice.CandidatesMessage;
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
public class RpcMessageFromClientPeerCommand extends CommandBase {
    private int remoteId;
    private CandidatesMessage message;

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

        server.iceMessageFromRPC(message);
        peer.sendCommand(new ServerPeerStatusCommand(remoteId, true, server.getIceState()));
    }

}
