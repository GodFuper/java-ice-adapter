package com.faforever.iceadapter.dto.command.relay.manual.from_client;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerStatusCommand;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class StartRelayServerCommand extends CommandBase {
    private int remoteId;
    private String remoteLogin;
    private boolean localOffer;
    private AllowCombination combination;

    @Override
    public void execute(Peer peer) {
        if (peer instanceof MainPeer mainPeer) {
            addServer(mainPeer);
        }
    }

    public void addServer(MainPeer peer) {
        boolean status = false;
        IceState iceState = null;
        if (peer.isServerMode()) {
            ServerPeer server = peer.getRelays().computeIfAbsent(remoteId, (id) -> {
                ServerPeer serverPeer = new ServerPeer(peer, id, remoteLogin, localOffer);
                serverPeer.init();
                serverPeer.setCombination(combination);
                serverPeer.initModules();
                return serverPeer;
            });
            peer.addServerPeer(server);
            status = true;
            iceState = server.getState();
        }
        peer.sendCommand(new ServerPeerStatusCommand(remoteId, status, iceState));
    }
}
