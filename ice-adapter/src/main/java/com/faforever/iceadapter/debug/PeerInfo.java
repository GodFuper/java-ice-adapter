package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import javafx.beans.property.*;
import lombok.Data;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;

import java.util.Objects;
import java.util.StringJoiner;

@Data
public class PeerInfo implements PeerEventListener {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty login = new SimpleStringProperty();
    private final StringProperty connected = new SimpleStringProperty();
    private final StringProperty localCand = new SimpleStringProperty();
    private final StringProperty remoteCand = new SimpleStringProperty();
    private final StringProperty pairConnection = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty();
    private final StringProperty agent = new SimpleStringProperty();
    private final StringProperty offer = new SimpleStringProperty();
    private final StringProperty rtt = new SimpleStringProperty();
    private final StringProperty lastRecv = new SimpleStringProperty();
    private final StringProperty echosReceived = new SimpleStringProperty();
    private final BooleanProperty allowHost = new SimpleBooleanProperty();
    private final BooleanProperty allowReflexive = new SimpleBooleanProperty();
    private final BooleanProperty allowRelay = new SimpleBooleanProperty();

    // Конструктор
    public PeerInfo(int id, String login) {
        this.id.set(id);
        this.login.set(login);
    }

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
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

    public void update(Peer peer) {
        getConnected().set(String.valueOf(peer.isConnected()));

        getPairConnection().set(peer.getStrCandidateTypes("\n"));

        getState().set(String.valueOf(peer.getState()));
        getAgent().set(mapToStrForAgent(peer));

        getOffer().set(String.valueOf(peer.isLocalOffer()));
        getRtt().set(peer.getAverageRtt()
                .map(Math::round)
                .map(String::valueOf)
                .orElse("–"));
        getLastRecv().set(peer.getLastReceived()
                .map(ts -> "%.1fs ago".formatted((System.currentTimeMillis() - ts) / 1000f))
                .orElse("never"));
        getEchosReceived().set("%s/%s".formatted(String.valueOf(peer.countEchosReceived()), String.valueOf(peer.countInvalidEchosReceived())));
        getAllowHost().set(peer.isAllowHost());
        getAllowReflexive().set(peer.isAllowReflexive());
        getAllowRelay().set(peer.isAllowRelay());
    }

    private String mapToStrForAgent(Peer peer) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.setEmptyValue("-");
        peer.getAgentState()
                .map(String::valueOf)
                .ifPresent(joiner::add);
        peer.getActiveCandidatePair()
                .map(CandidatePair::getState)
                .map(String::valueOf)
                .ifPresent(joiner::add);
        return joiner.toString();
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) object;
        return Objects.equals(id.get(), peerInfo.id.get());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id.get());
    }
}
