package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.IceTrigger;
import com.faforever.iceadapter.services.impl.ConnectServiceControlledImpl;
import com.faforever.iceadapter.services.impl.ConnectServiceHandler;
import com.faforever.iceadapter.services.impl.ConnectServiceNotControlledImpl;
import com.faforever.iceadapter.services.impl.IceAsyncImpl;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.TrayIcon;
import kotlin.Pair;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
@NoArgsConstructor
public class GameSession implements IceGameSession {

    private static final List<TransportAddress> PUBLIC_STUN_SERVERS = List.of(
            new TransportAddress("stun.cloudflare.com", 3478, Transport.UDP),
            new TransportAddress("stun.l.google.com", 19302, Transport.UDP),
            new TransportAddress("stun.sipgate.net", 3478, Transport.UDP));

    private static final List<IceServer> iceServers = new ArrayList<>();

    public static List<IceServer> getAllServers() {
        return iceServers;
    }

    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

    private final IceAsync iceAsync = new IceAsyncImpl(ExecutorHolder.getExecutor(), ExecutorHolder.getScheduledExecutor());
    private final ConnectService controlledConnectService = new ConnectServiceControlledImpl(this, iceAsync);
    private final ConnectService notControlledConnectService = new ConnectServiceNotControlledImpl(this, iceAsync);
    private final ConnectService connectServiceHandler = new ConnectServiceHandler(controlledConnectService, notControlledConnectService);
    private final IceTrigger iceTrigger = new IceTrigger(iceAsync, connectServiceHandler);

    @Getter
    @Setter
    private volatile boolean gameEnded = false;

    /**
     * Initiates a connection to a peer (ICE)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public int connectToPeer(String remotePlayerLogin,
                             int remotePlayerId,
                             boolean offer,
                             int preferredPort,
                             boolean allowHost,
                             boolean allowReflexive,
                             boolean allowRelay) {
        if (peers.containsKey(remotePlayerId)) {
            reCreatePeer(remotePlayerId);
            debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
            return peers.get(remotePlayerId).getLocalPort();
        }
        Peer peer = new Peer(remotePlayerId, remotePlayerLogin, offer, preferredPort, getLobbyPort());
        peer.setAllows(allowHost, allowReflexive, allowRelay);
        peer.initModules(iceAsync);
        peer.addEventListener(iceTrigger);
        peer.startInitPeer();
        peers.put(remotePlayerId, peer);
        debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
        return peer.getLocalPort();
    }

    private void reCreatePeer(Integer remotePlayerId) {
        Peer reconnectPeer = peers.get(remotePlayerId);
        if (Objects.nonNull(reconnectPeer)) {
            String remotePlayerLogin = reconnectPeer.getRemoteLogin();
            boolean offer = reconnectPeer.isLocalOffer();
            int port = reconnectPeer.getLocalPort();
            boolean isAllowHost = reconnectPeer.isAllowHost();
            boolean isAllowReflexive = reconnectPeer.isAllowReflexive();
            boolean isAllowRelay = reconnectPeer.isAllowRelay();
            reconnectPeer.setAllows(isAllowHost, isAllowReflexive, isAllowRelay);

            disconnectFromPeer(remotePlayerId);
            connectToPeer(remotePlayerLogin, remotePlayerId, offer, port, isAllowHost, isAllowReflexive, isAllowRelay);
        }
    }

    /**
     * Disconnects from a peer (ICE)
     */
    public void disconnectFromPeer(int remotePlayerId) {
        Peer removedPeer = peers.remove(remotePlayerId);
        if (removedPeer != null) {
            removedPeer.close();
            debug().disconnectFromPeer(remotePlayerId);
        }
        // TODO: still testing connectivity and reporting disconnect via rpc, why???
        // TODO: still attempting to ICE
    }

    public void reconnectToPeer(Integer remotePlayerId, Boolean allowHost, Boolean allowReflexive, Boolean allowRelay) {
        Peer reconnectPeer = peers.get(remotePlayerId);
        if (Objects.nonNull(reconnectPeer)) {

            boolean isAllowHost = allowHost != null ? allowHost : reconnectPeer.isAllowHost();
            boolean isAllowReflexive = allowReflexive != null ? allowReflexive : reconnectPeer.isAllowReflexive();
            boolean isAllowRelay = allowRelay != null ? allowRelay : reconnectPeer.isAllowRelay();
            reconnectPeer.setAllows(isAllowHost, isAllowReflexive, isAllowRelay);
            iceAsync.runAsync(reconnectPeer, reconnectPeer::lostConnect);
        }
    }

    /**
     * Stops the connection to all peers and all ice agents
     */
    public void close() {
        log.info("Closing gameSession");
        peers.values().forEach(Peer::close);
        peers.clear();
    }


    public List<IceServer> getIceServers() {
        return iceServers;
    }

    public List<IceServer> getFilteredIceServers() {
        List<IceServer> allIceServers = iceServers;
        if (IceAdapter.getPingCount() <= 0 || allIceServers.isEmpty()) {
            return allIceServers;
        }

        // Try servers with acceptable latency
        List<IceServer> viableIceServers =
                allIceServers.stream().filter(IceServer::hasAcceptableLatency).collect(Collectors.toList());
        if (!viableIceServers.isEmpty()) {
            log.info("Using all viable ice servers: {}",
                    viableIceServers.stream()
                            .map(it -> "["
                                    + it.getTurnAddresses().stream()
                                    .map(TransportAddress::toString)
                                    .collect(Collectors.joining(", "))
                                    + "]")
                            .collect(Collectors.joining(", ")));
            return viableIceServers;
        }

        log.info("Using all ice servers: {}",
                allIceServers.stream()
                        .map(it -> "["
                                + it.getTurnAddresses().stream()
                                .map(TransportAddress::toString)
                                .collect(Collectors.joining(", "))
                                + "]")
                        .collect(Collectors.joining(", ")));
        return allIceServers;
    }

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        iceServers.clear();

        PUBLIC_STUN_SERVERS.forEach(stunServer -> {
            var iceServer = new IceServer();
            iceServer.getStunAddresses().add(stunServer);
            iceServers.add(iceServer);
        });

        if (iceServersData.isEmpty()) {
            return;
        }

        Pair<List<IceServer>, Set<CoturnServer>> pair = IceServer.mapperFromMap(iceServersData);

        iceServers.addAll(pair.getFirst());
        debug().updateCoturnList(pair.getSecond());

        log.info(
                "Ice Servers set, total addresses: {}",
                iceServers.stream()
                        .mapToInt(iceServer -> iceServer.getStunAddresses().size()
                                + iceServer.getTurnAddresses().size())
                        .sum());
    }

    public void onIceMessageReceived(Peer peer, CandidatesMessage message) {
        iceAsync.runAsync(peer, () -> connectServiceHandler.onIceMessageReceived(peer, message));
    }

    @Override
    public int getLobbyPort() {
        return GPGNetServer.getLobbyPort();
    }

    @Override
    public int getMyId() {
        return IceAdapter.getId();
    }

    @Override
    public void sendToRpc(CandidatesMessage message) {
        IceAdapter.INSTANCE.getRpcService().onIceMsg(message);
    }

    @Override
    public void onConnected(Peer peer, boolean connected) {
        IceAdapter.INSTANCE.getRpcService().onConnected(getMyId(), peer.getRemoteId(), connected);
    }

    @Override
    public void showMessage(String message) {
        TrayIcon.showMessage(message);
    }
}
