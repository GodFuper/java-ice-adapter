package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.rpc.RPCService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import kotlin.Pair;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.CandidateType;

import java.util.Comparator;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
public class DebugAdapterImpl implements Adapter {

    private final IceAdapter iceAdapter;

    public DebugAdapterImpl(IceAdapter iceAdapter) {
        this.iceAdapter = iceAdapter;
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
        return iceAdapter.getRpcService() != null ? iceAdapter.getRpcService().getPort() : -1;
    }

    @Override
    public int getGpgNetPort() {
        return iceAdapter.getGpgNetServer() != null ? iceAdapter.getGpgNetServer().getGpgNetPort() : -1;
    }

    @Override
    public int getLobbyPort() {
        return iceAdapter.getGpgNetServer() != null ? iceAdapter.getGpgNetServer().getLobbyPort() : -1;
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
        // Состояние подключения к FA
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
        GameSession gameSession = IceAdapter.getGameSession();
        if (gameSession == null) {
            return FXCollections.emptyObservableList();
        }

        return FXCollections.observableArrayList(
                gameSession.getPeers()
                        .values()
                        .stream()
                        .sorted((p1, p2) -> Comparator.comparingInt(Peer::getRemoteId).compare(p1, p2))
                        .map(this::toPeerInfo)
                        .collect(Collectors.toList())
        );
    }

    private PeerInfo toPeerInfo(Peer peer) {
        PeerInfo info = new PeerInfo(
                peer.getRemoteId(),
                peer.getRemoteLogin()
        );

        info.getConnected().set(String.valueOf(peer.isConnected()));

        StringJoiner pairCandidates = new StringJoiner("\n");
        for (Pair<CandidateType, CandidateType> pair : peer.getCandidateTypes()) {
            pairCandidates.add("%s<->%s".formatted(pair.getFirst(), pair.getSecond()));
        }
        info.getPairConnection().set(pairCandidates.toString());

        info.getState().set(String.valueOf(peer.getState()));
        info.getAgent().set(peer.getAgentState().map(String::valueOf).orElse("-"));

        info.getOffer().set(String.valueOf(peer.isLocalOffer()));
        info.getRtt().set(peer.getAverageRtt()
                .map(Math::round)
                .map(String::valueOf)
                .orElse("–"));
        info.getLastRecv().set(peer.getLastReceived()
                .map(ts -> "%.1fs ago".formatted((System.currentTimeMillis() - ts) / 1000f))
                .orElse("never"));
        info.getEchosReceived().set("%s/%s".formatted(peer.countEchosReceived()
                .map(String::valueOf)
                .orElse("–"), peer.countInvalidEchosReceived()
                .map(String::valueOf)
                .orElse("–")));

        info.getAllowHost().set(peer.isAllowHost());
        info.getAllowReflexive().set(peer.isAllowReflexive());
        info.getAllowRelay().set(peer.isAllowRelay());

        return info;
    }

    @Override
    public String getLogBuffer() {
//        return Debug.getLogBuffer(); // Предполагается, что Debug сохраняет логи
        return "";
    }

    @Override
    public void reconnect(PeerInfo peer, boolean allowHost, boolean allowReflexive, boolean allowRelay) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        iceAdapter.reconnectToPeer(id, allowHost, allowReflexive, allowRelay);
    }

    @Override
    public void shutdown() {
        IceAdapter.close(0);
    }
}
