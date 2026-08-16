package com.faforever.iceadapter.dto.command.relay.manual.from_server;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_client.SetIceStateCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_client.StopRelayServerCommand;
import com.faforever.iceadapter.ice.IceState;
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
public class ServerPeerStatusCommand extends CommandBase {
    private int remoteId;
    private boolean active;
    private IceState iceState;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            initClient(mainPeer);
        }
    }

    private void initClient(MainPeer mainPeer) {
        Peer client = mainPeer.getLinkClient().get(remoteId);
        if (client == null) {
            if (active) {
                mainPeer.sendCommand(new StopRelayServerCommand(remoteId));
            }
            return;
        }

        if (!active) {
            client.setRelayPeer(null);
            return;
        }

        if (iceState == null) {
            IceState newState = IceState.NEW;
            mainPeer.sendCommand(new SetIceStateCommand(remoteId, newState));
            client.setIceStateWithoutTrigger(newState);
        } else {
            client.setIceState(iceState);
        }
    }
}
