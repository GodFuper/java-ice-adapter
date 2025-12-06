package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.IceTrigger;
import com.faforever.iceadapter.services.MessagesService;
import com.faforever.iceadapter.services.impl.*;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.PingWrapper;
import com.faforever.iceadapter.util.TrayIcon;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    private static final String STUN = "stun";
    private static final String TURN = "turn";

    private static final List<TransportAddress> PUBLIC_STUN_SERVERS = List.of(
            new TransportAddress("stun.cloudflare.com", 3478, Transport.UDP),
            new TransportAddress("stun.l.google.com", 19302, Transport.UDP),
            new TransportAddress("stun.sipgate.net", 3478, Transport.UDP));

    private static final List<IceServer> iceServers = new ArrayList<>();

    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

    private final IceAsync iceAsync = new IceAsyncImpl(ExecutorHolder.getExecutor(), ExecutorHolder.getScheduledExecutor());
    private final ConnectService controlledConnectService = new ConnectServiceControlledImpl(this, iceAsync);
    private final ConnectService notControlledConnectService = new ConnectServiceNotControlledImpl(this, iceAsync);
    private final ConnectService connectServiceHandler = new ConnectServiceHandler(this, controlledConnectService, notControlledConnectService);
    private final IceTrigger iceTrigger = new IceTriggerImpl(iceAsync, connectServiceHandler);
    private final MessagesService messagesService = new MessageServiceImpl(this, connectServiceHandler);
    private final IcePeerAdapter icePeerAdapter = new IcePeerAdapterImpl(connectServiceHandler, messagesService);

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
            return peers.get(remotePlayerId).getLocalPort();
        }
        Peer peer = new Peer(this, iceTrigger, remotePlayerId, remotePlayerLogin, offer, preferredPort);
        peer.setAllows(allowHost, allowReflexive, allowRelay);
        peer.initModules(this, icePeerAdapter);
        peer.init();
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
            reconnectPeer.reconnect();

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
            reconnectPeer.reconnect();
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

        // For caching RTT to a given host (the same host can appear in multiple urls)
        LoadingCache<String, CompletableFuture<OptionalDouble>> hostRTTCache = CacheBuilder.newBuilder()
                .build(new CacheLoader<>() {
                    @Override
                    public CompletableFuture<OptionalDouble> load(String host) {
                        return PingWrapper.getLatency(host, IceAdapter.getPingCount())
                                .thenApply(OptionalDouble::of)
                                .exceptionally(ex -> OptionalDouble.empty());
                    }
                });

        Set<CoturnServer> coturnServers = new HashSet<>();

        for (Map<String, Object> iceServerData : iceServersData) {
            IceServer iceServer = new IceServer();

            if (iceServerData.containsKey("username")) {
                iceServer.setTurnUsername((String) iceServerData.get("username"));
            }
            if (iceServerData.containsKey("credential")) {
                iceServer.setTurnCredential((String) iceServerData.get("credential"));
            }

            if (iceServerData.containsKey("urls")) {
                List<String> urls;
                Object urlsData = iceServerData.get("urls");
                if (urlsData instanceof List) {
                    urls = (List<String>) urlsData;
                } else {
                    urls = Collections.singletonList((String) iceServerData.get("url"));
                }

                urls.stream()
                        .map(stringUrl -> {
                            try {
                                return new URI(stringUrl);
                            } catch (Exception e) {
                                log.warn("Invalid ICE server URI: {}", stringUrl);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .forEach(uri -> {
                            String host = uri.getHost();
                            int port = uri.getPort() == -1 ? 3478 : uri.getPort();
                            Transport transport = Optional.ofNullable(uri.getQuery()).stream()
                                    .flatMap(query -> Arrays.stream(query.split("&")))
                                    .map(param -> param.split("="))
                                    .filter(param -> param.length == 2)
                                    .filter(param -> param[0].equals("transport"))
                                    .map(param -> param[1])
                                    .map(Transport::parse)
                                    .findFirst()
                                    .orElse(Transport.UDP);

                            TransportAddress address = new TransportAddress(host, port, transport);
                            switch (uri.getScheme()) {
                                case STUN -> iceServer.getStunAddresses().add(address);
                                case TURN -> iceServer.getTurnAddresses().add(address);
                                default -> log.warn("Invalid ICE server protocol: {}", uri);
                            }

                            if (IceAdapter.getPingCount() > 0) {
                                iceServer.setRoundTripTime(hostRTTCache.getUnchecked(host));
                            }

                            coturnServers.add(new CoturnServer("n/a", host, port, null));
                        });
            }

            iceServers.add(iceServer);
        }

        debug().updateCoturnList(coturnServers);

        log.info(
                "Ice Servers set, total addresses: {}",
                iceServers.stream()
                        .mapToInt(iceServer -> iceServer.getStunAddresses().size()
                                + iceServer.getTurnAddresses().size())
                        .sum());
    }

    public void onIceMessageReceived(CandidatesMessage message) {
        connectServiceHandler.onIceMessageReceived(message);
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
