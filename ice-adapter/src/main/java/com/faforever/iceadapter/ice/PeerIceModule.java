package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.TrayIcon;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;
import static com.faforever.iceadapter.ice.IceState.*;
import static com.faforever.iceadapter.util.DatagramSocketUtils.MAX_SIZE_PACKET;

@Getter
@Slf4j
@RequiredArgsConstructor
public class PeerIceModule {
    @Setter
    private static RPCService rpcService;

    private static final int MINIMUM_PORT = 6112; // PORT (range +1000) to be used by ICE for communicating, each peer needs a seperate port
    private static final long FORCE_SRFLX_RELAY_INTERVAL = 2 * 60 * 1000; // 2 mins, the interval in which multiple connects have to happen to force srflx/relay
    private static final int FORCE_SRFLX_COUNT = 1;
    private static final int FORCE_RELAY_COUNT = 2;

    private final Peer peer;

    private Agent agent;
    private IceMediaStream mediaStream;
    private Component component;

    private volatile IceState iceState = NEW;
    @Getter
    private volatile boolean connected = false;
    private volatile Thread listenerThread;

    private PeerTurnRefreshModule turnRefreshModule;

    // Checks the connection by sending echo requests and initiates a reconnect if needed
    private final PeerConnectivityCheckerModule connectivityChecker = new PeerConnectivityCheckerModule(this);

    // A list of the timestamps of initiated connectivity attempts, used to detect if relay/srflx should be forced
    private final List<Long> connectivityAttemptTimes = new ArrayList<>();
    // How often have we been waiting for a response to local candidates/offer
    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);

    private final Lock lockInit = new ReentrantLock();
    private final Lock lockLostConnection = new ReentrantLock();
    private final Lock lockMessageReceived = new ReentrantLock();

    // Prevent concurrent restarts / double-init
    private final AtomicBoolean restartRequested = new AtomicBoolean(false);
    private final AtomicBoolean initiating = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private final AtomicInteger consecutiveSendFailures = new AtomicInteger(0);
    private static final int SEND_FAILURE_THRESHOLD = 3;

    /**
     * Updates the current iceState and informs the client via RPC
     *
     * @param newState the new State
     */
    private void setState(IceState newState) {
        this.iceState = newState;
        rpcService.onIceConnectionStateChanged(IceAdapter.getId(), peer.getRemoteId(), iceState.getMessage());
        debug().peerStateChanged(this.peer);
    }

    /**
     * Will start the ICE Process
     */
    void initiateIce() {

        // ensure only one concurrent initiation
        if (!initiating.compareAndSet(false, true)) {
            log.debug("{} initiateIce called but already initiating - ignoring", getLogPrefix());
            return;
        }

        LockUtil.executeWithLock(lockInit, () -> {
            try {
                if (peer.isClosing()) {
                    log.warn("{} Peer not connected anymore, aborting reinitiation of ICE", getLogPrefix());
                    return;
                }

                if (iceState != NEW && iceState != DISCONNECTED) {
                    log.warn("{} ICE already in progress, aborting re initiation. current state: {}", getLogPrefix(), iceState.getMessage());
                    return;
                }

                setState(GATHERING);
                log.info("{} Initiating ICE for peer", getLogPrefix());

                createAgent();
                gatherCandidates();
            } finally {
                initiating.set(false);
            }
        });
    }

    /**
     * Creates an agent and media stream for handling the ICE
     */
    private void createAgent() {
        if (agent != null) {
            try {
                agent.free();
            } catch (Exception e) {
                log.warn("{} Error freeing existing agent", getLogPrefix(), e);
            }
        }

        agent = new Agent();
        agent.setControlling(peer.isLocalOffer());

        mediaStream = agent.createMediaStream("faData");
    }

    /**
     * Gathers all local candidates, packs them into a message and sends them to the other peer via RPC
     */
    private void gatherCandidates() {
        log.info("{} Gathering ice candidates", getLogPrefix());

        // For STUN all servers are relevant (latency is not an issue)
        GameSession.getIceServers().stream().flatMap(s -> s.getStunAddresses().stream()).forEach(address -> {
            log.info("{} Add STUN harvester for {}", getLogPrefix(), address.getHostName());
            agent.addCandidateHarvester(new StunCandidateHarvester(address));
        });

        // TURN is latency sensitive
        List<IceServer> iceServers = getViableIceServers();
        iceServers.forEach(iceServer -> iceServer.getTurnAddresses().forEach(address -> {
            var harvester = new TurnCandidateHarvester(address, new LongTermCredential(iceServer.getTurnUsername(), iceServer.getTurnCredential()));
            log.info("{} Add TURN harvester for {}", getLogPrefix(), address.getHostName());
            agent.addCandidateHarvester(harvester);
        }));

        CompletableFuture<Void> gatheringFuture = CompletableFuture.runAsync(() -> {
            try {
                component = agent.createComponent(mediaStream, ThreadLocalRandom.current().nextInt(MINIMUM_PORT, MINIMUM_PORT + 999), MINIMUM_PORT, MINIMUM_PORT + 1000);
                DatagramSocketUtils.resizeBuffer(component.getSocket());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, IceAdapter.getExecutor());

        CompletableFuture.runAsync(() -> {
            if (!gatheringFuture.isDone()) {
                gatheringFuture.cancel(true);
            }
        }, CompletableFuture.delayedExecutor(5000, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));

        try {
            gatheringFuture.join();
        } catch (CompletionException e) {
            // Completed exceptionally
            log.error("{} Error while creating stream component/gathering candidates", getLogPrefix(), e);
            // schedule a safe reconnect rather than abrupt onConnectionLost
            safeScheduleReconnect(1000);
            return;
        } catch (CancellationException e) {
            // was cancelled due to timeout
            log.error("{} Gathering candidates timed out", getLogPrefix(), e);
            safeScheduleReconnect(1000);
            return;
        }


        long previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
        CandidatesMessage localCandidatesMessage = CandidateUtil.packCandidates(IceAdapter.getId(),
                peer.getRemoteId(),
                agent,
                component,
                previousConnectivityAttempts < FORCE_SRFLX_COUNT && peer.isAllowHost(),
                previousConnectivityAttempts < FORCE_RELAY_COUNT && peer.isAllowReflexive(), peer.isAllowRelay());
        log.debug("{} Sending own candidates to {}, offered candidates: {}",
                getLogPrefix(),
                peer.getRemoteId(),
                localCandidatesMessage.candidates()
                        .stream()
                        .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                        .collect(Collectors.joining(", ")));
        setState(AWAITING_CANDIDATES);
        rpcService.onIceMsg(localCandidatesMessage);


        // Make sure to abort the connection process and reinitiate when we haven't received an answer to our offer in 6
        // seconds, candidate packet was probably lost
        final int currentAwaitingCandidatesEventId = awaitingCandidatesEventId.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            if (peer.isClosing()) {
                log.warn("{} Peer {} not connected anymore, aborting reinitiation of ICE", getLogPrefix(), peer.getRemoteId());
                return;
            }
            if (iceState == AWAITING_CANDIDATES && currentAwaitingCandidatesEventId == awaitingCandidatesEventId.get()) {
                // schedule a safe reconnect rather than immediate hard onConnectionLost
                safeScheduleReconnect(0);
            }
        }, CompletableFuture.delayedExecutor(6000, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
    }

    private List<IceServer> getViableIceServers() {
        List<IceServer> allIceServers = GameSession.getIceServers();
        if (IceAdapter.getPingCount() <= 0 || allIceServers.isEmpty()) {
            return allIceServers;
        }

        // Try servers with acceptable latency
        List<IceServer> viableIceServers = allIceServers.stream().filter(IceServer::hasAcceptableLatency).collect(Collectors.toList());
        if (!viableIceServers.isEmpty()) {
            log.info("Using all viable ice servers: {}", viableIceServers.stream().map(it -> "[" + it.getTurnAddresses().stream().map(TransportAddress::toString).collect(Collectors.joining(", ")) + "]").collect(Collectors.joining(", ")));
            return viableIceServers;
        }


        log.info("Using all ice servers: {}", allIceServers.stream().map(it -> "[" + it.getTurnAddresses().stream().map(TransportAddress::toString).collect(Collectors.joining(", ")) + "]").collect(Collectors.joining(", ")));
        return allIceServers;
    }

    /**
     * Starts harvesting local candidates if in answer mode, then initiates the actual ICE process
     *
     * @param remoteCandidatesMessage
     */
    public void onIceMessageReceived(CandidatesMessage remoteCandidatesMessage) {
        LockUtil.executeWithLock(lockMessageReceived, () -> {
            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, discarding ice message", getLogPrefix());
                return;
            }

            // Start ICE async as it's blocking and this is the RPC thread
            CompletableFuture.runAsync(() -> {
                log.debug("{} Got IceMsg for peer, offered candidates: {}", getLogPrefix(), remoteCandidatesMessage.candidates().stream().map(it -> it.type().toString() + "(" + it.protocol() + ")").collect(Collectors.joining(", ")));

                if (peer.isLocalOffer()) {
                    if (iceState != AWAITING_CANDIDATES) {
                        log.warn("{} Received candidates unexpectedly, current state: {}", getLogPrefix(), iceState.getMessage());
                        return;
                    }

                } else {
                    // Check if we are already processing an ICE offer and if so stop it
                    if (iceState != NEW && iceState != DISCONNECTED) {
                        log.info("{} Received new candidates/offer, stopping...", getLogPrefix());
                        restartRequested.set(true);
                        return;
                    }

                    // Answer mode, initialize agent and gather candidates
                    initiateIce();
                }

                setState(CHECKING);

                long previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
                CandidateUtil.unpackCandidates(remoteCandidatesMessage,
                        agent,
                        component,
                        mediaStream,
                        previousConnectivityAttempts < FORCE_SRFLX_COUNT && peer.isAllowHost(),
                        previousConnectivityAttempts < FORCE_RELAY_COUNT && peer.isAllowReflexive(), peer.isAllowRelay());

                startIce();
            }, IceAdapter.getExecutor());
        });
    }

    /**
     * Runs the actual connectivity establishment, candidates have been exchanged and need to be checked
     */
    private void startIce() {
        connectivityAttemptTimes.add(0, System.currentTimeMillis());


        log.debug("{} Starting ICE for peer {}", getLogPrefix(), peer.getRemoteId());
        agent.startConnectivityEstablishment();


// Wait for termination/completion of the agent
        long iceStartTime = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted() && agent.getState() != IceProcessingState.COMPLETED) { // TODO include more?, maybe stop on COMPLETED, is that to early?
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                log.error("{} Interrupted while waiting for ICE", getLogPrefix(), e);
                safeScheduleReconnect(0);
                return;
            }


            if (agent.getState() == IceProcessingState.FAILED) { // TODO null pointer due to no agent?
                safeScheduleReconnect(0);
                return;
            }


            if (System.currentTimeMillis() - iceStartTime > 15_000) {
                log.error("{} ABORTING ICE DUE TO TIMEOUT", getLogPrefix());
                safeScheduleReconnect(0);
                return;
            }
        }


        log.debug("{} ICE terminated, connected, selected candidate pair: {} <-> {}", getLogPrefix(), component.getSelectedPair().getLocalCandidate().getType().toString(), component.getSelectedPair().getRemoteCandidate().getType().toString());


        // We are connected
        connected = true;
        rpcService.onConnected(IceAdapter.getId(), peer.getRemoteId(), true);
        setState(CONNECTED);


        if (component.getSelectedPair().getLocalCandidate().getType() == CandidateType.RELAYED_CANDIDATE) {
            turnRefreshModule = new PeerTurnRefreshModule(this, (RelayedCandidate) component.getSelectedPair().getLocalCandidate());
        }


        if (peer.isLocalOffer()) {
            connectivityChecker.start();
        }


        listenerThread = new Thread(this::listener);
        listenerThread.start();
    }

    /**
     * Connection has been lost, ice failed or we received a new offer
     * Will close agent, stop listener and connectivity checker thread and change state to disconnected
     * Will then reinitiate ICE
     */
    public void onConnectionLost() {
        if (!lockLostConnection.tryLock()) {
            log.debug("{} onConnectionLost already running - ignoring concurrent call", getLogPrefix());
            return;
        }
        try {
            if (iceState == DISCONNECTED && !connected) {
                log.warn("{} Lost connection, albeit already in ice state disconnected", getLogPrefix());
                return; // avoid double cleanup
            }

            IceState previousState = getIceState();

            stopping.set(true);

            if (listenerThread != null) {
                listenerThread.interrupt();
                listenerThread = null;
            }

            if (turnRefreshModule != null) {
                try {
                    turnRefreshModule.close();
                } catch (Exception e) {
                    log.warn("{} Error closing turnRefreshModule", getLogPrefix(), e);
                }
                turnRefreshModule = null;
            }

            connectivityChecker.stop();

            if (connected) {
                connected = false;
                log.warn("{} ICE connection has been lost for peer", getLogPrefix());
                rpcService.onConnected(IceAdapter.getId(), peer.getRemoteId(), false);
            }

            setState(DISCONNECTED);

            if (agent != null) {
                try {
                    agent.free();
                } catch (Exception e) {
                    log.warn("{} Error freeing agent during onConnectionLost", getLogPrefix(), e);
                }
                agent = null;
                mediaStream = null;
                component = null;
            }

            debug().peerStateChanged(this.peer);

            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, aborting onConnectionLost of ICE", getLogPrefix());
                return;
            }

            if (peer.getGameSession().isGameEnded()) {
                log.warn("{} GAME ENDED, ABORTING onConnectionLost of ICE for peer ", getLogPrefix());
                return;
            }

            if (previousState == CONNECTED) {
                TrayIcon.showMessage("Reconnecting to %s (connection lost)".formatted(this.peer.getRemoteLogin()));
            }

            // If a restart was requested earlier, perform it now in a controlled fashion
            if (restartRequested.getAndSet(false) && peer.isLocalOffer()) {
                safeScheduleReconnect(0);
                return;
            }

            if (previousState == CONNECTED && peer.isLocalOffer()) {
                // We were connected before, retry immediately
                safeScheduleReconnect(0);
            } else if (peer.isLocalOffer()) {
                // Last ice attempt didn't succeed, so wait a bit
                safeScheduleReconnect(5000);
            }
        } finally {
            stopping.set(false);
            lockLostConnection.unlock();
        }
    }

    /**
     * Data received from FA, prepends prefix and sends it via ICE to the other peer
     *
     * @param faData
     * @param length
     */
    void onFaDataReceived(byte[] faData, int length) {
        byte[] data = new byte[length + 1];
        data[0] = 'd';
        System.arraycopy(faData, 0, data, 1, length);
        sendViaIce(data, 0, data.length);
    }

    /**
     * Send date via ice to the other peer
     *
     * @param data
     * @param offset
     * @param length
     */
    void sendViaIce(byte[] data, int offset, int length) {
        if (connected && component != null) {
            try {
                // enforce a safe MTU for TURN relays - avoid too large messages that trigger "Message too long"
                if (component.getSelectedPair() != null && component.getSelectedPair().getLocalCandidate().getType() == CandidateType.RELAYED_CANDIDATE && length > MAX_SIZE_PACKET) {
                    log.warn("{} Packet too large for relay, dropping or fragmenting (len={})", getLogPrefix(), length);
                    // You may implement fragmentation here. For now: drop to avoid tearing down ICE.
                    return;
                }

                component.getSocket().send(new DatagramPacket(data, offset, length));
                // success -> reset failure counter
                consecutiveSendFailures.set(0);
            } catch (IOException e) {
                int fails = consecutiveSendFailures.incrementAndGet();
                log.warn("{} Failed to send data via ICE (attempt {}): {}", getLogPrefix(), fails, e.toString());
                // Only treat as full connection loss after several consecutive failures
                if (fails >= SEND_FAILURE_THRESHOLD) {
                    log.error("{} Too many consecutive send failures ({}), scheduling reconnect", getLogPrefix(), fails);
                    safeScheduleReconnect(0);
                }
            } catch (NullPointerException e) {
                log.error("Component is null", e);
            }
        }
    }

    /**
     * Listens for data incoming via ice socket
     */
    public void listener() {
        log.debug("{} Now forwarding data from ICE to FA for peer", getLogPrefix());
        Component localComponent = component;

        byte[] data = new byte[MAX_SIZE_PACKET];
        while (!Thread.currentThread().isInterrupted() && IceAdapter.getGameSession() == peer.getGameSession()) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length);
                localComponent.getSocket().receive(packet);

                connectivityChecker.notifyTrafficReceived();
                if (packet.getLength() == 0) {
                    continue;
                }

                if (data[0] == 'd') {
                    // Received data
                    peer.onIceDataReceived(data, 1, packet.getLength() - 1);
                } else if (data[0] == 'e') {
                    // Received echo req/res
                    if (peer.isLocalOffer()) {
                        connectivityChecker.echoReceived(data, 0, packet.getLength());
                    } else {
                        sendViaIce(data, 0, packet.getLength()); // Turn around, send echo back
                    }
                } else {
                    log.warn("{} Received invalid packet, first byte: 0x{}, length: {}", getLogPrefix(), data[0], packet.getLength());
                }

            } catch (IOException e) { // TODO: nullpointer from localComponent.xxxx????
                log.warn("{} Error while reading from ICE adapter", getLogPrefix(), e);
                if (component == localComponent) {
                    safeScheduleReconnect(0);
                }
                return;
            }
        }

        log.debug("{} No longer listening for messages from ICE", getLogPrefix());
    }

    void close() {
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        if (turnRefreshModule != null) {
            turnRefreshModule.close();
        }
        if (agent != null) {
            agent.free();
        }
        connectivityChecker.stop();
    }

    public long getConnectivityAttempsInThePast(final long millis) {
        // copy list to avoid concurrency issues
        return new ArrayList<>(connectivityAttemptTimes).stream().filter(time -> time > (System.currentTimeMillis() - millis)).count();
    }

    public String getLogPrefix() {
        return "ICE %s:".formatted(peer.getPeerIdentifier());
    }

    /**
     * Schedules a reconnect in a safe way: sets restartRequested and runs initiateIce after given delay.
     * This avoids tearing down an agent while its stun stack is still processing messages on the same socket,
     * which caused duplicate STUN responses in logs.
     */
    private void safeScheduleReconnect(long delayMillis) {
        // avoid scheduling multiple reconnects concurrently
        if (!restartRequested.compareAndSet(false, true)) {
            log.debug("{} Reconnect already requested - ignoring duplicate", getLogPrefix());
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
            }


            // Ensure we run cleanup on executor thread to avoid races
            CompletableFuture.runAsync(() -> {
                try {
                    onConnectionLost();
                } catch (Exception e) {
                    log.warn("{} Error during safe onConnectionLost", getLogPrefix(), e);
                }


                // now initiate if still needed
                if (peer.isLocalOffer()) {
                    initiateIce();
                }
            }, IceAdapter.getExecutor());
        }, IceAdapter.getExecutor());
    }
}
