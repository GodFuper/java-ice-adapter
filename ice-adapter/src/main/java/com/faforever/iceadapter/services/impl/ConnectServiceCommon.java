package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.*;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

import java.util.concurrent.*;

import static com.faforever.iceadapter.ice.IceState.*;

@Slf4j
@RequiredArgsConstructor
public abstract class ConnectServiceCommon {
    protected static final String LOCK_CONNECT = "ConnectServiceImpl";
    protected static final String FAF_MEDIA_STREAM = "faData";
    protected static final int MINIMUM_PORT = 6112;
    protected static final int MAXIMUM_PORT = 7112;
    protected static final int TIMEOUT_ON_CHECKING = 15000;
    protected final IceGameSession iceGameSession;
    protected final IceAsync iceAsync;

    public void onChangeIceState(Peer peer, IceState oldState, IceState iceState) {
        if (peer == null || iceState == null) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
            switch (iceState) {
                case NEW -> onIceStateNew(peer);
                case GATHERING -> onIceStateGathering(peer);
                case AWAITING_CANDIDATES -> onIceAwaitingCandidates(peer);
                case CHECKING -> onIceStateChecking(peer);
                case CONNECTED -> onIceStateConnected(peer);
                case COMPLETED -> onIceStateCompleted(peer);
                case DISCONNECTED -> onIceStateDisconnected(peer, oldState);
                default -> log.error("Unknown Ice State {}", iceState);
            }
        });
    }

    abstract void onIceStateNew(Peer peer);

    abstract void onIceStateGathering(Peer peer);

    abstract void onIceAwaitingCandidates(Peer peer);

    abstract void onIceStateChecking(Peer peer);

    abstract void onIceStateConnected(Peer peer);

    abstract void onIceStateCompleted(Peer peer);

    abstract void onIceStateDisconnected(Peer peer, IceState oldState);

    protected void createAgent(Peer peer) {
        Agent oldAgent = peer.getAgent();

        if (oldAgent != null) {
            closeAgent(oldAgent);
        }

        log.info("Creating agent");
        Agent agent = new Agent();
        agent.setControlling(peer.isLocalOffer());

        peer.setAgent(agent);
        peer.setMediaStream(agent.createMediaStream(FAF_MEDIA_STREAM));
    }

    protected void gatherCandidates(Peer peer) {
        log.info("Gathering ice candidates");

        Agent agent = peer.getAgent();
        IceMediaStream mediaStream = peer.getMediaStream();

        // For STUN all servers are relevant (latency is not an issue)
        iceGameSession.getIceServers().stream()
                .flatMap(s -> s.getStunAddresses().stream())
                .forEach(address -> {
                    log.info("Add STUN harvester for {}", address.getHostName());
                    agent.addCandidateHarvester(new StunCandidateHarvester(address));
                });

        // TURN is latency sensitive
        iceGameSession.getFilteredIceServers().forEach(iceServer -> iceServer.getTurnAddresses().forEach(address -> {
            var harvester = new TurnCandidateHarvester(address, new LongTermCredential(iceServer.getTurnUsername(), iceServer.getTurnCredential()));
            log.info("Add TURN harvester for {}", address.getHostName());
            agent.addCandidateHarvester(harvester);
        }));

        CompletableFuture<Void> gatheringFuture = iceAsync.runAsync(peer, () -> createComponent(agent, mediaStream));

        iceAsync.runAsyncDelay(peer, () -> {
            if (!gatheringFuture.isDone()) {
                gatheringFuture.cancel(true);
            }
        }, 10000);

        boolean success = true;
        try {
            gatheringFuture.join();
        } catch (CompletionException e) {
            log.error("Error creating component", e);
            success = false;
        } catch (CancellationException e) {
            log.error("Gathering candidates timed out", e);
            success = false;
        }

        if (!success) {
            connectLost(peer);
            return;
        }

        for (Component component : mediaStream.getComponents()) {
            CandidatesMessage candidatesMessage = CandidateUtil.packCandidates(
                    iceGameSession.getMyId(),
                    peer.getRemoteId(),
                    agent,
                    component,
                    true,
                    true,
                    true);
            log.debug("Sending own candidates, offered candidates: {}", candidatesMessage.toStrCandidates());

            iceGameSession.sendToRpc(candidatesMessage);
        }
    }

    protected void onDisconnected(Peer peer, IceState oldState) {
        log.info("ICE state disconnected");
        Agent agent = peer.getAgent();
        if (agent != null) {
            closeAgent(agent);
            peer.setAgent(null);
        }

        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, aborting onConnectionLost of ICE");
            return;
        }

        if (iceGameSession.isGameEnded()) {
            log.warn("GAME ENDED, ABORTING onConnectionLost of ICE for peer ");
            return;
        }

        if (oldState == CONNECTED) {
            iceGameSession.showMessage("Reconnecting to %s (connection lost)".formatted(peer.getRemoteLogin()));
        }
    }

    protected void connectLost(Peer peer) {
        log.info("Lost connection");

        if (peer.getIceState() == DISCONNECTED) {
            log.warn("Lost connection, albeit already in ice state disconnected");
            return;
        }

        peer.stopModules();

        iceGameSession.onConnected(peer, peer.isConnected());

        peer.setIceState(DISCONNECTED);
    }

    protected boolean checking(Peer peer) {
        log.debug("Checking ICE for peer");
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        PeerConnectionSuccessMonitor monitor = new PeerConnectionSuccessMonitor(TIMEOUT_ON_CHECKING, () -> {
            future.complete(true);
        }, () -> {
            future.complete(false);
        });
        monitor.start(peer);
        Agent agent = peer.getAgent();
        agent.startConnectivityEstablishment();

        try {
            return future.get(TIMEOUT_ON_CHECKING, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Timeout while waiting for connection ICE");
            return false;
        }
    }

    protected void onConnected(Peer peer) {
        log.info("ICE state connected");

        log.debug("ICE terminated, connected, candidate pair: {} ", peer.getStrCandidateTypes("|"));

        iceGameSession.onConnected(peer, true);

        peer.startModules();
    }

    protected void asyncTimeoutAwaitingCandidates(int eventId, Peer peer) {
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
            if (peer.isClosing()) {
                log.warn("Peer not connected anymore, aborting reinitiation of ICE");
                return;
            }
            int actualEventId = peer.getAwaitingCandidatesEventId().get();

            if (eventId != actualEventId) {
                return;
            }
            if (peer.getIceState() == AWAITING_CANDIDATES) {
                connectLost(peer);
            }
        });
    }

    protected void createComponent(Agent agent, IceMediaStream mediaStream) {
        try {
            Component component = agent.createComponent(mediaStream, ThreadLocalRandom.current().nextInt(MINIMUM_PORT, MAXIMUM_PORT), MINIMUM_PORT, MAXIMUM_PORT);
            DatagramSocketUtils.resizeBuffer(component.getSocket());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void closeAgent(Agent agent) {
        log.info("Close agent");
        try {
            for (IceMediaStream stream : agent.getStreams()) {
                for (Component streamComponent : stream.getComponents()) {
                    stream.removeComponent(streamComponent);
                }
                agent.removeStream(stream);
            }
            agent.free();
        } catch (Exception e) {
            log.warn("Error freeing existing agent", e);
        }
    }

    abstract void onConnectionLost(Peer peer);
}
