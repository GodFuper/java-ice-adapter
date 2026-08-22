package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.dto.KcpPeerView;
import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.dto.ServerPeerView;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import kotlin.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;

@Slf4j
@RequiredArgsConstructor
public class UIAdapterImpl implements UIAdapter {

    private final IceAdapter iceAdapter;

    private final Map<Integer, PeerView> uiPeers = new ConcurrentHashMap<>();
    private final Map<Pair<Integer, Integer>, ServerPeerView> uiServerPeers = new ConcurrentHashMap<>();
    private final Map<Integer, KcpPeerView> uiKcpPeers = new ConcurrentHashMap<>();

    private Optional<IceGameSession> getGameSession() {
        return Optional.ofNullable(iceAdapter.getGameSession());
    }

    @Override
    public String getVersion() {
        return IceAdapter.getVersion();
    }

    @Override
    public String getUsername() {
        return IceAdapter.getLogin();
    }

    @Override
    public int getUserId() {
        return IceAdapter.getId();
    }

    @Override
    public int getRpcPort() {
        return Optional.ofNullable(iceAdapter.getRpcService())
                .map(RPCService::getPort)
                .orElse(-1);
    }

    @Override
    public int getGpgNetPort() {
        return Optional.ofNullable(iceAdapter.getGpgNetServer())
                .map(GPGNetServer::getGpgNetPort)
                .orElse(-1);
    }

    @Override
    public int getLobbyPort() {
        return Optional.ofNullable(iceAdapter.getGpgNetServer())
                .map(GPGNetServer::getLobbyPort)
                .orElse(-1);
    }

    @Override
    public String getRpcServerStatus() {
        RPCService rpc = iceAdapter.getRpcService();
        return rpc != null ? rpc.getHost() : "Not found";
    }

    @Override
    public String getRpcClientStatus() {
        return "N/A";
    }

    @Override
    public String getGpgNetServerStatus() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.isServerRunning() ? "Running" : "Stopped";
    }

    @Override
    public String getGpgNetClientStatus() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.isConnected() ? "Connected" : "Disconnected";
    }

    @Override
    public String getGameState() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.getGameState().isPresent()
                ? server.getGameState().get().name()
                : "UNKNOWN";
    }

    @Override
    public ObservableList<ServerPeerView> getServerPeerInfoList() {
        List<ServerPeer> peers =
                getGameSession().map(IceGameSession::getServerPeers).orElse(Collections.emptyList());

        Set<Pair<Integer, Integer>> keys = peers.stream()
                .map(serverPeer -> new Pair<>(serverPeer.getFromId(), serverPeer.getRemoteId()))
                .collect(Collectors.toSet());

        Set<Pair<Integer, Integer>> ids = uiServerPeers.keySet();

        if (!Objects.equals(ids, keys)) {
            ids.stream().filter(pair -> !keys.contains(pair)).forEach(uiServerPeers::remove);
        }

        return FXCollections.observableArrayList(peers.stream()
                .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                .map(this::toServerPeerInfo)
                .collect(Collectors.toList()));
    }

    @Override
    public ObservableList<KcpPeerView> getKcpPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> kcpPeerIds = peers.values().stream()
                .map(Peer::getRemoteId)
                .collect(Collectors.toSet());

        uiKcpPeers.keySet().stream()
                .filter(id -> !kcpPeerIds.contains(id))
                .forEach(uiKcpPeers::remove);

        return FXCollections.observableArrayList(peers.values().stream()
                .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                .map(this::toKcpPeerInfo)
                .collect(Collectors.toList()));
    }

    @Override
    public ObservableList<PeerView> getPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> ids = peers.keySet();

        if (!Objects.equals(uiPeers.keySet(), ids)) {
            uiPeers.keySet().stream().filter(id -> !ids.contains(id)).forEach(uiPeers::remove);
        }

        peers.values().forEach(peer -> {
            debug().peerStateChanged(peer);
            debug().peerConnectivityUpdate(peer);
        });

        return FXCollections.observableArrayList(peers.values().stream()
                .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                .map(this::toPeerInfo)
                .collect(Collectors.toList()));
    }

    @Override
    public ObservableList<PeerView> getRelayPeersInfoList(int id) {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        return FXCollections.observableArrayList(peers.values().stream()
                .filter(peer -> peer.isCanSelectForRelayPeerById(id))
                .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                .map(this::toPeerInfo)
                .collect(Collectors.toList()));
    }

    @Override
    public PeerView getPeerInfo(int id) {
        return uiPeers.get(id);
    }

    @Override
    public ObservableList<IceServerView> getIceServersList() {
        List<IceServer> servers =
                getGameSession().map(IceGameSession::getIceServers).orElse(Collections.emptyList());

        return FXCollections.observableArrayList(
                servers.stream().map(IceServerView::new).toList());
    }

    @Override
    public void setEnabledIceServer(IceServerView view, boolean enabled) {
        if (view == null) {
            return;
        }
        IceServer iceServer = view.getServer();
        iceServer.setEnabled(enabled);
        iceServer.setAuto(false);
    }

    private PeerView toPeerInfo(Peer peer) {
        PeerView info = uiPeers.computeIfAbsent(peer.getRemoteId(), id -> {
            PeerView uiInfo = new PeerView(peer.getRemoteId(), peer.getRemoteLogin());
            peer.addEventListener(uiInfo);
            return uiInfo;
        });

        info.update(peer);

        return info;
    }

    private ServerPeerView toServerPeerInfo(ServerPeer peer) {
        ServerPeerView info = uiServerPeers.computeIfAbsent(new Pair<>(peer.getFromId(), peer.getRemoteId()), id -> {
            ServerPeerView uiInfo = new ServerPeerView(
                    peer.getRemoteId(),
                    peer.getRemoteLogin(),
                    peer.getFrom().getRemoteId(),
                    peer.getFrom().getRemoteLogin());
            peer.addEventListener(uiInfo);
            return uiInfo;
        });

        info.update(peer);

        return info;
    }

    private KcpPeerView toKcpPeerInfo(Peer peer) {
        KcpPeerView info = uiKcpPeers.computeIfAbsent(peer.getRemoteId(), id ->
                new KcpPeerView(peer.getRemoteId(), peer.getRemoteLogin()));
        info.update(peer);

        return info;
    }

    @Override
    public void reconnect(PeerView peer) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(Peer::reconnect);
    }

    @Override
    public void setAllowCombination(PeerView peer, AllowCombination combination) {
        if (peer == null || combination == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(p -> p.setCombination(combination, true));
    }

    @Override
    public void setStrategy(PeerView peer, IceAgentStrategy newStrategy) {
        if (peer == null || newStrategy == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(p -> p.setAgentStrategy(newStrategy));
    }

    @Override
    public void setRelayPeer(PeerView peer, PeerView relayPeerView) {
        if (peer == null) {
            return;
        }

        int idRelayPeer = relayPeerView != null ? relayPeerView.getId().get() : -1;

        if (peer.getAdditionalInfo().getRelayPeerId().get() == idRelayPeer) {
            return;
        }

        int id = peer.getId().get();
        Optional<Peer> optPeer = getGameSession().flatMap(session -> session.getPeer(id));

        Peer relayPeer = getGameSession()
                .flatMap(session -> session.getPeer(idRelayPeer))
                .orElse(null);
        optPeer.ifPresent(p -> p.setRelayPeer(relayPeer));
    }

    @Override
    public void setAdditionalPacketForwarding(PeerView peer, boolean enabled) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setAdditionalPacketForwarding(enabled));
    }

    @Override
    public void setPacketLossProbability(PeerView peer, float probability) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setPacketLossProbability(probability));
    }

    @Override
    public void setPacketDelay(PeerView peer, int delay) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setPacketDelay(delay));
    }

    @Override
    public void setPacketJitter(PeerView peer, float jitter) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setPacketJitter(jitter));
    }

    @Override
    public void setPeerSendMode(PeerView peer, PeerSendMode peerSendMode) {
        if (peer == null || peerSendMode == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setSendMode(peerSendMode));
    }

    @Override
    public boolean isEnabledManualCombinationConnection() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(option -> option.isManualCombinationConnection() && !option.isForceRelay())
                .orElse(false);
    }

    @Override
    public boolean isEnabledManualStrategyConnection() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isManualStrategyConnection)
                .orElse(false);
    }

    @Override
    public boolean isEnabledAdditionalPeerInfo() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isAdditionalInfoPeer)
                .orElse(false);
    }

    @Override
    public void shutdown() {
        IceAdapter.close(0);
    }
}
