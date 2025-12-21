package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
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
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
@RequiredArgsConstructor
public class GameSession implements IceGameSession {

    private static final List<IceServer> iceServers = createIceServers();

    public static List<IceServer> getAllServers() {
        return iceServers;
    }

    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

    private final RPCService rpcService;
    private final IceOptions options;

    private final IceAsync iceAsync = new IceAsyncImpl(ExecutorHolder.getExecutor(), ExecutorHolder.getScheduledExecutor());
    private final ConnectService controlledConnectService = new ConnectServiceControlledImpl(this, iceAsync);
    private final ConnectService notControlledConnectService = new ConnectServiceNotControlledImpl(this, iceAsync);
    private final ConnectService connectServiceHandler = new ConnectServiceHandler(controlledConnectService, notControlledConnectService);
    private final IceTrigger iceTrigger = new IceTrigger(iceAsync, connectServiceHandler);
    private final IceServerChecker iceServerChecker;

    @Getter
    @Setter
    private volatile boolean gameEnded = false;

    public GameSession(RPCService rpcService, IceOptions options) {
        this.rpcService = rpcService;
        this.options = options;
        iceServerChecker = new IceServerChecker(options, this);
    }

    /**
     * Initiates a connection to a peer (ICE)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public int connectToPeer(String remotePlayerLogin,
                             int remotePlayerId,
                             boolean offer,
                             int preferredPort,
                             AllowCombination combination) {
        if (peers.containsKey(remotePlayerId)) {
            reCreatePeer(remotePlayerId);
            debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
            return peers.get(remotePlayerId).getLocalPort();
        }
        Peer peer = new Peer(remotePlayerId, remotePlayerLogin, offer, preferredPort, getLobbyPort(), getDisabledModules());
        peer.init();
        peer.setCombination(combination);
        peer.initModules();
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
            AllowCombination combination = reconnectPeer.getCombination();

            disconnectFromPeer(remotePlayerId);
            connectToPeer(remotePlayerLogin, remotePlayerId, offer, port, combination);
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

    /**
     * Stops the connection to all peers and all ice agents
     */
    public void close() {
        log.info("Closing gameSession");
        peers.values().forEach(Peer::close);
        peers.clear();
        iceServerChecker.stop();
    }


    public List<IceServer> getIceServers() {
        return iceServers;
    }

    @Override
    public Optional<Peer> getPeer(int peerId) {
        return Optional.ofNullable(peers.get(peerId));
    }

    private Set<PeerModule> getDisabledModules() {
        Set<PeerModule> disabledModules = new HashSet<>();
        if (!options.isManualStrategyConnection()) {
            disabledModules.add(PeerModule.CHANGE_AGENT_STRATEGY);
        }
        if (options.isForceRelay() || options.isManualCombinationConnection()) {
            disabledModules.add(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE);
        }
        return disabledModules;
    }

    public static List<IceServer> createIceServers() {
        List<IceServer> iceServers = new ArrayList<>();
        addDefaultIceServers(iceServers);
        return iceServers;
    }

    public static void addDefaultIceServers(List<IceServer> iceServers) {
        iceServers.addAll(IceServer.createPublicServers());
    }

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     */
    public void setIceServers(List<Map<String, Object>> iceServersData) {
        iceServers.clear();
        addDefaultIceServers(iceServers);

        if (iceServersData.isEmpty()) {
            return;
        }

        Pair<List<IceServer>, Set<CoturnServer>> pair = IceServer.mapperFromMap(iceServersData);

        iceServers.addAll(pair.getFirst());
        debug().updateCoturnList(pair.getSecond());

        iceServerChecker.start();
        log.info("Ice Servers set, total addresses: {}", iceServers.size());
    }

    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        iceAsync.runAsync("onIceMessageFromRPC", peer, () -> connectServiceHandler.onMessageFromRPC(peer, message));
    }

    @Override
    public int getLobbyPort() {
        return GPGNetServer.getStaticLobbyPort();
    }

    @Override
    public int getMyId() {
        return options.getId();
    }

    @Override
    public void sendToRpc(CandidatesMessage message) {
        rpcService.onIceMsg(message);
    }

    @Override
    public void onConnected(Peer peer, boolean connected) {
        rpcService.onConnected(getMyId(), peer.getRemoteId(), connected);
    }

    @Override
    public void showMessage(String message) {
        TrayIcon.showMessage(message);
    }
}
