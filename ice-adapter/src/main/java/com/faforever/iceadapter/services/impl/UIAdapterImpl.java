package com.faforever.iceadapter.services.impl;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.dto.ControlMessageType;
import com.faforever.iceadapter.dto.ControlTrafficRecord;
import com.faforever.iceadapter.dto.ControlTrafficStats;
import com.faforever.iceadapter.dto.ControlTrafficView;
import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.dto.WebRtcDataChannelView;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import com.faforever.iceadapter.webrtc.WebRtcSession.DataChannelStats;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UIAdapterImpl implements UIAdapter {

    private final IceAdapter iceAdapter;

    private final Map<Integer, PeerView> uiPeers = new ConcurrentHashMap<>();
    private final Map<Integer, WebRtcPeerView> uiWebRtcPeers = new ConcurrentHashMap<>();
    private final Map<String, WebRtcDataChannelView> uiDataChannels = new ConcurrentHashMap<>();
    private final Map<ControlMessageType, ControlTrafficView> uiControlTrafficMap = new EnumMap<>(ControlMessageType.class);
    private final ObservableList<PeerView> peerInfoList = FXCollections.observableArrayList();
    private final ObservableList<WebRtcPeerView> webRtcPeerInfoList = FXCollections.observableArrayList();
    private final ObservableList<WebRtcDataChannelView> webRtcDataChannelsList = FXCollections.observableArrayList();
    private final ObservableList<ControlTrafficView> controlTrafficViewList = FXCollections.observableArrayList();
    private final ObservableList<IceServerView> iceServersList = FXCollections.observableArrayList();

    {
        for (ControlMessageType type : ControlMessageType.values()) {
            ControlTrafficView view = new ControlTrafficView(type);
            uiControlTrafficMap.put(type, view);
            controlTrafficViewList.add(view);
        }
    }

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
    public ObservableList<WebRtcPeerView> getWebRtcPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> peerIds = peers.keySet();

        if (!Objects.equals(uiWebRtcPeers.keySet(), peerIds)) {
            uiWebRtcPeers.keySet().removeIf(id -> !peerIds.contains(id));
        }

        for (Peer peer : peers.values()) {
            toWebRtcPeerInfo(peer);
        }

        boolean structureChanged = webRtcPeerInfoList.size() != peers.size();
        if (!structureChanged) {
            for (int i = 0; i < webRtcPeerInfoList.size(); i++) {
                if (!peerIds.contains(webRtcPeerInfoList.get(i).getPeerId().get())) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            List<WebRtcPeerView> sorted = peers.values().stream()
                    .sorted(Comparator.comparingInt(Peer::getRemoteId))
                    .map(this::toWebRtcPeerInfo)
                    .toList();
            webRtcPeerInfoList.setAll(sorted);
        }

        return webRtcPeerInfoList;
    }

    @Override
    public ObservableList<WebRtcDataChannelView> getWebRtcDataChannelsList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<String> activeKeys = new HashSet<>();
        List<WebRtcDataChannelView> currentChannels = new ArrayList<>();

        for (Peer peer : peers.values()) {
            int peerId = peer.getRemoteId();
            String login = peer.getRemoteLogin();
            WebRtcSession session = peer.getWebRtcSession();
            if (session != null) {
                Map<String, DataChannelStats> dcStatsMap = session.getStats().getDataChannels();
                for (Map.Entry<String, DataChannelStats> entry : dcStatsMap.entrySet()) {
                    String label = entry.getKey();
                    String key = peerId + ":" + label;
                    activeKeys.add(key);

                    WebRtcDataChannelView view =
                            uiDataChannels.computeIfAbsent(key, k -> new WebRtcDataChannelView(peerId, login, label));
                    view.update(login, entry.getValue());
                    currentChannels.add(view);
                }
            }
        }

        uiDataChannels.keySet().removeIf(k -> !activeKeys.contains(k));

        // Sort BEFORE comparison to get a stable, deterministic order.
        // Without this, ConcurrentHashMap.entrySet() iteration order is non-deterministic,
        // causing spurious structureChanged=true on every tick → setAll() → table flicker.
        currentChannels.sort(Comparator.comparingInt((WebRtcDataChannelView v) -> v.getPeerId().get())
                .thenComparing(v -> v.getLabel().get()));

        boolean structureChanged = webRtcDataChannelsList.size() != currentChannels.size();
        if (!structureChanged) {
            for (int i = 0; i < currentChannels.size(); i++) {
                if (webRtcDataChannelsList.get(i) != currentChannels.get(i)) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            webRtcDataChannelsList.setAll(currentChannels);
        }

        return webRtcDataChannelsList;
    }

    @Override
    public ObservableList<ControlTrafficView> getControlTrafficViewList(Integer peerId) {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        long overallTotalBytes = 0;
        Map<ControlMessageType, long[]> aggregated = new EnumMap<>(ControlMessageType.class);
        for (ControlMessageType type : ControlMessageType.values()) {
            aggregated.put(type, new long[4]); // [sentBytes, recvBytes, sentMsgs, recvMsgs]
        }

        for (Peer peer : peers.values()) {
            if (peerId != null && peerId != -1 && peer.getRemoteId() != peerId) {
                continue;
            }
            WebRtcSession session = peer.getWebRtcSession();
            if (session != null) {
                ControlTrafficStats stats = session.getStats().getControlTrafficStats();
                if (stats != null) {
                    for (ControlMessageType type : ControlMessageType.values()) {
                        ControlTrafficRecord record = stats.getRecord(type);
                        if (record != null) {
                            long[] counts = aggregated.get(type);
                            long sBytes = record.getBytesSentCount();
                            long rBytes = record.getBytesReceivedCount();
                            long sMsgs = record.getMessagesSentCount();
                            long rMsgs = record.getMessagesReceivedCount();
                            counts[0] += sBytes;
                            counts[1] += rBytes;
                            counts[2] += sMsgs;
                            counts[3] += rMsgs;
                            overallTotalBytes += (sBytes + rBytes);
                        }
                    }
                }
            }
        }

        for (ControlMessageType type : ControlMessageType.values()) {
            long[] counts = aggregated.get(type);
            ControlTrafficView view = uiControlTrafficMap.get(type);
            if (view != null) {
                view.update(counts[0], counts[1], counts[2], counts[3], overallTotalBytes);
            }
        }

        return controlTrafficViewList;
    }

    @Override
    public ObservableList<PeerView> getPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> ids = peers.keySet();

        if (!Objects.equals(uiPeers.keySet(), ids)) {
            uiPeers.keySet().removeIf(id -> !ids.contains(id));
        }

        peers.values().forEach(peer -> {
            debug().peerStateChanged(peer);
            debug().peerConnectivityUpdate(peer);
            toPeerInfo(peer);
        });

        boolean structureChanged = peerInfoList.size() != peers.size();
        if (!structureChanged) {
            for (int i = 0; i < peerInfoList.size(); i++) {
                if (!ids.contains(peerInfoList.get(i).getId().get())) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            List<PeerView> sorted = peers.values().stream()
                    .sorted(Comparator.comparingInt(Peer::getRemoteId))
                    .map(this::toPeerInfo)
                    .toList();
            peerInfoList.setAll(sorted);
        }

        return peerInfoList;
    }

    @Override
    public PeerView getPeerInfo(int id) {
        return uiPeers.get(id);
    }

    @Override
    public ObservableList<IceServerView> getIceServersList() {
        List<IceServer> servers =
                getGameSession().map(IceGameSession::getIceServers).orElse(Collections.emptyList());

        if (iceServersList.size() != servers.size()) {
            iceServersList.setAll(servers.stream().map(IceServerView::new).toList());
        }

        return iceServersList;
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

    private WebRtcPeerView toWebRtcPeerInfo(Peer peer) {
        WebRtcPeerView info = uiWebRtcPeers.computeIfAbsent(
                peer.getRemoteId(), id -> new WebRtcPeerView(peer.getRemoteId(), peer.getRemoteLogin()));
        info.update(peer, isShowIpAddresses());

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
    public void setCombination(PeerView peer, AllowCombination combination) {
        if (peer == null || combination == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(p -> p.setCombination(combination, true));
    }

    @Override
    public boolean isShowIpAddresses() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isShowIpAddresses)
                .orElse(false);
    }

    @Override
    public boolean isShowAllowCombination() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isShowAllowCombination)
                .orElse(false);
    }

    @Override
    public void shutdown() {
        IceAdapter.close(0);
    }
}
