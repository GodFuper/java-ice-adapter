package com.faforever.iceadapter.ice.peer;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class MainPeer extends Peer {

    private final int fromId;

    @Setter
    private boolean serverMode;
    @Setter
    private boolean allowRelay = false;

    /**
     * peer -> server -> peer(remoteId)
     */
    private final Map<Integer, ServerPeer> relays = new ConcurrentHashMap<>();

    /**
     * client(remoteId) -> peer -> server -> peer
     */
    private final Map<Integer, Peer> linkClient = new ConcurrentHashMap<>();

    private MainPeer relayPeer;

    public MainPeer(int fromId, int remoteId, String remoteLogin, boolean localOffer, int preferredPort, int lobbyPort, boolean serverMode, Set<PeerModule> disabledModules) {
        super(remoteId, remoteLogin, localOffer, preferredPort, lobbyPort, disabledModules);
        this.serverMode = serverMode;
        this.fromId = fromId;
    }

    @Override
    public Optional<Integer> getRelayPeerId() {
        return Optional.ofNullable(relayPeer)
                .map(Peer::getRemoteId);
    }

    public void setRelayPeer(MainPeer relay, boolean isEvent) {
        if (isEvent) {
            super.setRelayPeer(relay);
        } else {
            this.relayPeer = relay;
        }
    }

    @Override
    public boolean isAllowRelay() {
        return allowRelay;
    }

    @Override
    public void close() {
        if (relayPeer != null) {
            setRelayPeer(null);
        }

        if (!relays.isEmpty()) {
            relays.forEach((id, serverPeer) -> serverPeer.close());
        }

        super.close();
    }
}
