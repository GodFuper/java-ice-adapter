package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.services.RpcConnection;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Setter
@Getter
public class TestGameSession extends GameSession {
    private int lobbyPort = 0;
    private Set<PeerModule> disabledModules;

    public TestGameSession(RpcConnection rpcConnection, IceOptions options, Set<PeerModule> disabledModules) {
        super(rpcConnection, options);
        this.disabledModules = disabledModules;
    }

    @Override
    protected Set<PeerModule> getAdditionalDisabledModules() {
        return disabledModules;
    }

    @Override
    public int getLobbyPort() {
        return lobbyPort;
    }
}
