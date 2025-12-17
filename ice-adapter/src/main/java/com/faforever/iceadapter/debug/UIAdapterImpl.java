package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
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

    private final Map<Integer, PeerInfo> uiPeers = new ConcurrentHashMap<>();

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
        // В текущей реализации нет явного "клиента" RPC, только сервер
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
    public ObservableList<PeerInfo> getPeerInfoList() {
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

    private PeerInfo toPeerInfo(Peer peer) {
        PeerInfo info = uiPeers.computeIfAbsent(peer.getRemoteId(), id -> {
            PeerInfo uiInfo = new PeerInfo(peer.getRemoteId(), peer.getRemoteLogin());
            peer.addEventListener(uiInfo);
            return uiInfo;
        });

        info.update(peer);

        return info;
    }

    @Override
    public void reconnect(PeerInfo peer) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(Peer::reconnect);
    }

    @Override
    public void setAllowCombination(PeerInfo peer, AllowCombination combination) {
        if (peer == null || combination == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setCombination(combination));
    }

    @Override
    public void setStrategy(PeerInfo peer, IceAgentStrategy newStrategy) {
        if (peer == null || newStrategy == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setAgentStrategy(newStrategy));
    }

    @Override
    public void shutdown() {
        IceAdapter.close(0);
    }
}
