package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;
import org.ice4j.ice.Agent;

import java.util.Objects;

@Data
public class ServerPeerView implements PeerEventListener {
    private final StringProperty main = new SimpleStringProperty();
    private final StringProperty remote = new SimpleStringProperty();
    private final BooleanProperty connected = new SimpleBooleanProperty();
    private final StringProperty localCand = new SimpleStringProperty();
    private final StringProperty remoteCand = new SimpleStringProperty();
    private final StringProperty pairConnection = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty();
    private final StringProperty agent = new SimpleStringProperty();
    private final StringProperty offer = new SimpleStringProperty();
    private final BooleanProperty allowHost = new SimpleBooleanProperty();
    private final BooleanProperty allowReflexive = new SimpleBooleanProperty();
    private final BooleanProperty allowRelay = new SimpleBooleanProperty();

    public ServerPeerView(int id, String login, int remoteId, String remoteLogin) {
        this.main.set(prettyPrint(login, id));
        this.remote.set(prettyPrint(remoteLogin, remoteId));
    }

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        update(peer);
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        update(peer);
    }

    @Override
    public void onAgentChange(Peer peer, Agent agent) {
        update(peer);
    }

    @Override
    public void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
        update(peer);
    }

    private String prettyPrint(String login, int id) {
        return "%s (ID: %d)".formatted(login, id);
    }

    public void update(Peer peer) {
        getConnected().set(peer.isConnected());

        getPairConnection().set(peer.getStrCandidateTypes("\n"));

        getState().set(String.valueOf(peer.getState()));
        getAgent().set(peer.getAgentState().map(String::valueOf).orElse("-"));

        getOffer().set(String.valueOf(peer.isLocalOffer()));
        AllowCombination combination = peer.getCombination();
        getAllowHost().set(combination.isAllowHost());
        getAllowReflexive().set(combination.isAllowReflexive());
        getAllowRelay().set(combination.isAllowRelay());
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        ServerPeerView that = (ServerPeerView) object;
        return Objects.equals(main, that.main) && Objects.equals(remote, that.remote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, remote);
    }
}
