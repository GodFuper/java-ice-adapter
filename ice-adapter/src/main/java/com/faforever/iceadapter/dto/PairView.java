package com.faforever.iceadapter.dto;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;

public class PairView {
    private final StringProperty localType = new SimpleStringProperty();
    private final StringProperty localAddress = new SimpleStringProperty();
    private final StringProperty localFoundation = new SimpleStringProperty();

    private final StringProperty remoteType = new SimpleStringProperty();
    private final StringProperty remoteAddress = new SimpleStringProperty();
    private final StringProperty remoteFoundation = new SimpleStringProperty();

    private final StringProperty pairType = new SimpleStringProperty();
    private final StringProperty pairState = new SimpleStringProperty();
    private final BooleanProperty isActive = new SimpleBooleanProperty();

    private CandidatePair candidatePair;

    public StringProperty localType() {
        return localType;
    }

    public StringProperty localAddress() {
        return localAddress;
    }

    public StringProperty localFoundation() {
        return localFoundation;
    }

    public StringProperty remoteType() {
        return remoteType;
    }

    public StringProperty remoteAddress() {
        return remoteAddress;
    }

    public StringProperty remoteFoundation() {
        return remoteFoundation;
    }

    public StringProperty pairType() {
        return pairType;
    }

    public StringProperty pairState() {
        return pairState;
    }

    public BooleanProperty isActive() {
        return isActive;
    }

    public CandidatePair getCandidatePair() {
        return candidatePair;
    }

    public void setCandidatePair(CandidatePair candidatePair) {
        this.candidatePair = candidatePair;
    }

    public void update(CandidatePair pair, float peerRtt) {
        this.candidatePair = pair;

        var local = pair.getLocalCandidate();
        var remote = pair.getRemoteCandidate();

        localType.set(formatType(local.getType()));
        localAddress.set("%s:%d".formatted(
                local.getTransportAddress().getAddress().getHostAddress(),
                local.getTransportAddress().getPort()));
        localFoundation.set(local.getFoundation());

        remoteType.set(formatType(remote.getType()));
        remoteAddress.set("%s:%d".formatted(
                remote.getTransportAddress().getAddress().getHostAddress(),
                remote.getTransportAddress().getPort()));
        remoteFoundation.set(remote.getFoundation());

        pairType.set("%s <-> %s".formatted(
                local.getType().toString(),
                remote.getType().toString()));
        pairState.set(pair.getState().toString());
    }

    private String formatType(CandidateType type) {
        return switch (type) {
            case HOST_CANDIDATE -> "host";
            case SERVER_REFLEXIVE_CANDIDATE -> "srflx";
            case PEER_REFLEXIVE_CANDIDATE -> "prflx";
            case RELAYED_CANDIDATE -> "relay";
            default -> type.toString().toLowerCase();
        };
    }
}
