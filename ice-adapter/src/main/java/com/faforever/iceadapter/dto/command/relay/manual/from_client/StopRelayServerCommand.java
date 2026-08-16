package com.faforever.iceadapter.dto.command.relay.manual.from_client;

import com.faforever.iceadapter.dto.command.CommandBase;
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
public class StopRelayServerCommand extends CommandBase {
    private int remoteId;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            removeServer(mainPeer);
        }
    }

    public void removeServer(MainPeer peer) {
        ServerPeer server = peer.getRelays().remove(remoteId);
        if (server == null) {
            return;
        }

        server.close();
    }
}
