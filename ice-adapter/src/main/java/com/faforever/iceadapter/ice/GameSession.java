package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.IceOptions.TransportMode;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.ServerPeer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.*;
import com.faforever.iceadapter.services.impl.*;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.webrtc.*;
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

    @Getter
    private IceOptions options;

    private final MessageService messageService = new MessageServiceImpl();
    private final IceAsync iceAsync =
            new IceAsyncImpl(ExecutorHolder.getExecutor(), ExecutorHolder.getScheduledExecutor());
    private final ConnectService controlledConnectService =
            new ConnectServiceControlledImpl(messageService, this, iceAsync);
    private final ConnectService notControlledConnectService =
            new ConnectServiceNotControlledImpl(messageService, this, iceAsync);
    private final ConnectService connectServiceHandler =
            new ConnectServiceHandler(controlledConnectService, notControlledConnectService);
    private IceTrigger iceTrigger;
    private IceServerChecker iceServerChecker;
    private WebRtcConnectService webRtcConnectService;

    @Getter
    @Setter
    private volatile boolean gameEnded = false;

    // WebRTC fields
    private TransportMode transportMode;
    private WebRtcConnectionFactory webRtcConnectionFactory;

    /**
     * Constructor for ICE or WebRTC mode (with TCP RPC).
     */
    public GameSession(RpcConnection rpcConnection, IceOptions options) {
        init(options);
        if (options.getTransport() == TransportMode.WEBRTC) {
            this.transportMode = TransportMode.WEBRTC;
            this.webRtcConnectionFactory = WebRtcConnectionFactory.getInstance();
            WebRtcConnectService controlledService = new WebRtcConnectServiceControlledImpl(
                    messageService, this, iceAsync);
            WebRtcConnectService notControlledService = new WebRtcConnectServiceNotControlledImpl(
                    messageService, this, iceAsync);
            this.webRtcConnectService = new WebRtcConnectServiceHandler(controlledService, notControlledService);
            this.iceTrigger = new IceTrigger(iceAsync, connectServiceHandler, webRtcConnectService, rpcConnection);
        } else {
            this.iceTrigger = new IceTrigger(iceAsync, connectServiceHandler, null, rpcConnection);
        }
    }

    /**
     * Constructor for WebRTC mode.
     */
    public GameSession(IceOptions options, WebRtcConnectionFactory webRtcConnectionFactory, RpcConnection rpcConnection) {
        init(options);
        this.transportMode = TransportMode.WEBRTC;
        this.webRtcConnectionFactory = webRtcConnectionFactory;

        // Create WebRTC connect service
        WebRtcConnectService controlledService = new WebRtcConnectServiceControlledImpl(
                messageService, this, iceAsync);
        WebRtcConnectService notControlledService = new WebRtcConnectServiceNotControlledImpl(
                messageService, this, iceAsync);
        webRtcConnectService = new WebRtcConnectServiceHandler(controlledService, notControlledService);

        this.iceTrigger = new IceTrigger(iceAsync, connectServiceHandler, webRtcConnectService, rpcConnection);
    }

    private void init(IceOptions options) {
        this.options = options;
        this.transportMode = options.getTransport();
        iceServerChecker = new IceServerChecker(options, this);
        iceServerChecker.start();
    }

    /**
     * Initiates a connection to a peer (ICE or WebRTC)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public int connectToPeer(
            String remotePlayerLogin,
            int remotePlayerId,
            boolean offer,
            int preferredPort,
            AllowCombination combination) {

        if (peers.containsKey(remotePlayerId)) {
            reCreatePeer(remotePlayerId);
            debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
            return peers.get(remotePlayerId).getLocalPort();
        }
        Set<PeerModule> allDisabled = new HashSet<>(getDisabledModules());
        allDisabled.addAll(getAdditionalDisabledModules());
        Peer peer = new MainPeer(
                options.getId(),
                remotePlayerId,
                remotePlayerLogin,
                offer,
                preferredPort,
                getLobbyPort(),
                options.isHostMode(),
                allDisabled);
        peer.init();
        peer.setCombination(combination);
        peer.setGameSession(this);
        peer.setSendMode(options.getSendMode());
        peer.initModules();
        peer.addEventListener(iceTrigger);
        peer.startInitPeer();
        peers.put(remotePlayerId, peer);
        debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
        return peer.getLocalPort();
    }


    /**
     * Disconnects from a peer (ICE or WebRTC)
     */
    public void disconnectFromPeer(int remotePlayerId) {
        Peer removedPeer = peers.remove(remotePlayerId);
        if (removedPeer != null) {
            removedPeer.close();
            debug().disconnectFromPeer(remotePlayerId);
        }
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
    public List<ServerPeer> getServerPeers() {
        List<ServerPeer> serverPeers = new ArrayList<>();

        peers.forEach((id, p) -> {
            if (p instanceof MainPeer peer) {
                serverPeers.addAll(peer.getRelays().values());
            }
        });

        return serverPeers;
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
        if (options.isForceRelay()) {
            disabledModules.add(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE);
        }

        if (transportMode == TransportMode.WEBRTC) {
            disabledModules.add(PeerModule.KCP_OFFERER_PEER_TO_PEER_TRANSPORT);
            disabledModules.add(PeerModule.PEER_LISTENER_MODULE);
            disabledModules.add(PeerModule.PEER_TO_PEER_SENDER);
            disabledModules.add(PeerModule.PAIR_SELECTOR);
            disabledModules.add(PeerModule.PEER_TURN_REFRESHER_MODULE);
            disabledModules.add(PeerModule.CHANGE_AGENT_STRATEGY);
            disabledModules.add(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE);
            disabledModules.add(PeerModule.AUTO_RELAY_CALCULATE_RTT);
            disabledModules.add(PeerModule.RELAY_CLIENT_MODULE);
            disabledModules.add(PeerModule.RELAY_SERVER_MODULE);
        } else {
            disabledModules.add(PeerModule.WEBRTC_PEER_TO_PEER_SENDER);
            disabledModules.add(PeerModule.WEBRTC_PEER_TO_PEER_LISTENER);
        }

        return disabledModules;
    }

    /**
     * Returns additional disabled modules for test subclasses.
     */
    protected Set<PeerModule> getAdditionalDisabledModules() {
        return Set.of();
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
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        iceServers.clear();
        addDefaultIceServers(iceServers);

        if (iceServersData.isEmpty()) {
            return;
        }

        Pair<List<IceServer>, Set<CoturnServer>> pair = IceServer.mapperFromMap(iceServersData);

        iceServers.addAll(pair.getFirst());
        debug().updateCoturnList(pair.getSecond());

        log.info("Ice Servers set, total addresses: {}", iceServers.size());
    }

    @Override
    public int getLobbyPort() {
        return GPGNetServer.getStaticLobbyPort();
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
}
