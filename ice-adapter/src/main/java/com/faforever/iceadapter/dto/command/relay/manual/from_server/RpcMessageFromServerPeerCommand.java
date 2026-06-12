package com.faforever.iceadapter.dto.command.relay.manual.from_server;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_client.StopRelayServerCommand;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RpcMessageFromServerPeerCommand extends CommandBase {
    private int remoteId;
    private CandidatesMessage message;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            executePeer(mainPeer);
        }
    }

    private void executePeer(MainPeer mainPeer) {
        Peer client = mainPeer.getLinkClient().get(remoteId);
        if (client == null) {
            mainPeer.sendCommand(new StopRelayServerCommand(remoteId));
            return;
        }

        client.sendToRpc(message);
    }


}
