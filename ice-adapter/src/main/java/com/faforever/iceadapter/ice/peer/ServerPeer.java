package com.faforever.iceadapter.ice.peer;

import lombok.Getter;

@Getter
public class ServerPeer extends Peer {

    private final MainPeer from;

    public ServerPeer(MainPeer from, int remoteId, String remoteLogin, boolean localOffer) {
        super(remoteId, remoteLogin, localOffer, 0, 0, PeerModule.getModulesDisableForServer());
        this.from = from;
    }

    @Override
    public int getFromId() {
        return from.getRemoteId();
    }

    @Override
    public void close() {
        if (from != null) {
            from.getRelays().remove(getRemoteId());
        }
        super.close();
    }
}
