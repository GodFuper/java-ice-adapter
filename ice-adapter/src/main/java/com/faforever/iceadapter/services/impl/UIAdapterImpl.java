package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
    public ObservableList<PeerView> getPeerInfoList() {
        Map<Integer, Peer> peers = getGameSession()
                .map(IceGameSession::getPeers)
                .orElse(Collections.emptyMap());

        Set<Integer> ids = peers.keySet();

        if (!Objects.equals(uiPeers.keySet(), ids)) {
            uiPeers.keySet().stream()
                    .filter(id -> !ids.contains(id))
                    .forEach(uiPeers::remove);
        }

        peers.values().forEach(peer -> {
            debug().peerStateChanged(peer);
            debug().peerConnectivityUpdate(peer);
        });

        return FXCollections.observableArrayList(
                peers.values()
                        .stream()
                        .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                        .map(this::toPeerInfo)
                        .collect(Collectors.toList())
        );
    }

    @Override
    public ObservableList<IceServerView> getIceServersList() {
        List<IceServer> servers = getGameSession().map(IceGameSession::getIceServers)
                .orElse(Collections.emptyList());

        return FXCollections.observableArrayList(servers.stream()
                .map(IceServerView::new)
                .toList());
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

    @Override
    public void reconnect(PeerView peer) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(Peer::reconnect);
    }

    @Override
    public void setAllowCombination(PeerView peer, AllowCombination combination) {
        if (peer == null || combination == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setCombination(combination));
    }

    @Override
    public void setStrategy(PeerView peer, IceAgentStrategy newStrategy) {
        if (peer == null || newStrategy == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setAgentStrategy(newStrategy));
    }

    @Override
    public boolean isEnabledManualCombinationConnection() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isManualCombinationConnection)
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
