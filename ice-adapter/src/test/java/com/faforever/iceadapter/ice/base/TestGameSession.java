package com.faforever.iceadapter.ice.base;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.RpcConnection;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Setter
@Getter
public class TestGameSession extends GameSession {
    private int lobbyPort = 0;
    private Set<PeerModule> disabledModules;
    private PeerSendMode sendModeOverride = PeerSendMode.DIRECT_ONLY;

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

    /**
     * Overrides the parent {@link #connectToPeer} to apply {@code kcpUdpOverride}
     * after the peer is created. The parent always calls {@code peer.setKcpUdpTransport(options.isKcpUdp())},
     * so we override it with the test-controlled flag.
     */
    @Override
    public int connectToPeer(
            String remotePlayerLogin,
            int remotePlayerId,
            boolean offer,
            int preferredPort,
            AllowCombination combination) {
        int result = super.connectToPeer(remotePlayerLogin, remotePlayerId, offer, preferredPort, combination);
        // Override the send mode set by the parent with the test-controlled value
        getPeer(remotePlayerId).ifPresent(peer -> peer.setSendMode(sendModeOverride));
        return result;
    }
}
