package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.*;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.util.CollectionUtils;
import javafx.beans.property.*;
import lombok.Data;
import org.ice4j.ice.Agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

@Data
public class PeerView implements PeerEventListener {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty login = new SimpleStringProperty();
    private final BooleanProperty connected = new SimpleBooleanProperty();
    private final StringProperty localCand = new SimpleStringProperty();
    private final StringProperty remoteCand = new SimpleStringProperty();
    private final StringProperty pairConnection = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty();
    private final StringProperty agent = new SimpleStringProperty();
    private final StringProperty offer = new SimpleStringProperty();
    private final StringProperty rtt = new SimpleStringProperty();
    private final StringProperty lastRecv = new SimpleStringProperty();
    private final StringProperty lastRelayRecv = new SimpleStringProperty();
    private final StringProperty echosReceived = new SimpleStringProperty();
    private final BooleanProperty peerRelaySupport = new SimpleBooleanProperty();
    private final AdditionalInfo additionalInfo = new AdditionalInfo();

    @Data
    public static class AdditionalInfo {
        private final BooleanProperty allowHost = new SimpleBooleanProperty();
        private final BooleanProperty allowReflexive = new SimpleBooleanProperty();
        private final BooleanProperty allowRelay = new SimpleBooleanProperty();
        private AllowCombination combination;
        private IceAgentStrategy agentStrategy;
        private Supplier<String> getFullCandidateInfo;
        private final IntegerProperty relayPeerId = new SimpleIntegerProperty(-1);
        private final BooleanProperty sendDirectAndRelay = new SimpleBooleanProperty(true);
        private PeerSendMode peerSendMode;
    }

    public PeerView(int id, String login) {
        this.id.set(id);
        this.login.set(login);
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

    public String prettyPrint() {
        return "%s (ID: %d)".formatted(login.get(), id.get());
    }

    public void update(Peer peer) {
        getConnected().set(peer.isConnected());

        getPairConnection().set(peer.getStrCandidateTypes("\n"));

        getState().set(String.valueOf(peer.getState()));
        getAgent().set(peer.getAgentState().map(String::valueOf).orElse("-"));

        getOffer().set(String.valueOf(peer.isLocalOffer()));
        getRtt().set(rttStr(peer));
        getLastRecv()
                .set(peer.getLastReceived()
                        .map(ts -> "%.1fs ago".formatted((System.currentTimeMillis() - ts) / 1000f))
                        .orElse("never"));
        getLastRelayRecv()
                .set(peer.getRelayLastReceived()
                        .map(ts -> "%.1fs ago".formatted((System.currentTimeMillis() - ts) / 1000f))
                        .orElse(""));
        getEchosReceived()
                .set("%s/%s"
                        .formatted(
                                String.valueOf(peer.countEchosReceived()),
                                String.valueOf(peer.countInvalidEchosReceived())));
        getPeerRelaySupport().set(peer.isSupportRelay());

        AllowCombination combination = peer.getCombination();
        getAdditionalInfo().getAllowHost().set(combination.isAllowHost());
        getAdditionalInfo().getAllowReflexive().set(combination.isAllowReflexive());
        getAdditionalInfo().getAllowRelay().set(combination.isAllowRelay());
        getAdditionalInfo().setAgentStrategy(peer.getAgentStrategy());
        getAdditionalInfo().getRelayPeerId().set(peer.getRelayPeerId().orElse(-1));
        getAdditionalInfo().setCombination(combination);

        getAdditionalInfo().setGetFullCandidateInfo(peer::getFullInfoSelectedPair);
        getAdditionalInfo().getSendDirectAndRelay().set(peer.isAdditionalPacketForwarding());
        getAdditionalInfo().setPeerSendMode(peer.getSendMode());
    }

    private static String rttStr(Peer peer) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("direct: %s"
                .formatted(peer.getAverageRtt()
                        .map(Math::round)
                        .map(String::valueOf)
                        .orElse("–")));
        Map<Integer, RelayPing> rtts = peer.getRtts();
        List<Integer> ids = peer.getBestRelays();
        if (!CollectionUtils.isEmpty(ids)) {
            for (Integer idPeer : ids) {
                RelayPing ping = rtts.get(idPeer);
                if (ping == null || !ping.isActual()) {
                    continue;
                }
                joiner.add("[%s]: %d".formatted(ping.getRemoteLogin(), Math.round(ping.getRtt())));
            }
        }
        return joiner.toString();
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        PeerView peerView = (PeerView) object;
        return Objects.equals(id.get(), peerView.id.get());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id.get());
    }
}
