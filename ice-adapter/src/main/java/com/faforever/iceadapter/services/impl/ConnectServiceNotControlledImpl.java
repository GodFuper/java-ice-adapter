package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.LockUtil;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.faforever.iceadapter.ice.IceState.*;

@Slf4j
public class ConnectServiceNotControlledImpl extends ConnectServiceCommon implements ConnectService {

    private final Map<Integer, Peer> newAndDisconnectedPeers = new ConcurrentHashMap<>();

    public ConnectServiceNotControlledImpl(IceGameSession iceGameSession, IceAsync iceAsync) {
        super(iceGameSession, iceAsync);
    }

    void onIceStateNew(Peer peer) {
        LockUtil.executeWithLock(lockMessageReceived, () -> newAndDisconnectedPeers.put(peer.getRemoteId(), peer));
    }

    void onIceStateGathering(Peer peer) {
        // Nothing to do. Job for controlled peer
    }

    void onIceAwaitingCandidates(Peer peer) {
        // Nothing to do. Job for controlled peer
    }

    void onIceStateCompleted(Peer peer) {
        log.info("ICE state completed");
    }

    @Override
    void onIceStateDisconnected(Peer peer, IceState oldState) {
        LockUtil.executeWithLock(lockMessageReceived, () -> newAndDisconnectedPeers.put(peer.getRemoteId(), peer));
        onDisconnected(peer, oldState);
    }

    void onIceStateChecking(Peer peer) {
        boolean connected = checking(peer);
        if (connected) {
            peer.setIceState(CONNECTED);
        }
    }

    void onIceStateConnected(Peer peer) {
        onConnected(peer);
    }

    @Override
    public void onConnectionLost(Peer peer) {
        if (peer == null) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> connectLost(peer));
    }

    @Override
    public void onIceMessageReceived(CandidatesMessage message) {
        if (message == null) {
            return;
        }
        Peer peer = iceGameSession.getPeers().get(message.srcId());
        onIceMessageReceived(peer, message);
    }

    @Override
    public void onIceMessageReceived(Peer peer, CandidatesMessage message) {
        if (message == null || peer == null) {
            return;
        }
        int idFrom = message.srcId();
        int idTo = message.destId();

        int myId = iceGameSession.getMyId();
        if (idTo != myId) {
            log.warn("Received a message that wasn't meant for me. My id is {}, message for {}", myId, idTo);
            return;
        }

        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        LockUtil.executeWithLock(lockMessageReceived, () -> {
            Peer peerWithNeedStatus = newAndDisconnectedPeers.get(idFrom);
            if (peerWithNeedStatus == null) {
                log.error("Peer with id {} wasn't not newAndDisconnectedPeers", idFrom);

                Map<Integer, Peer> allPeers = iceGameSession.getPeers();
                peerWithNeedStatus = allPeers.get(idFrom);

                if (peerWithNeedStatus == null) {
                    log.warn("Peer with id {} wasn't not found in game session", idFrom);
                    return;
                }
            }
            atomicBoolean.set(true);
        });

        if (!atomicBoolean.get()) {
            return;
        }

        iceAsync.runAsync(peer, () -> {
            LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
                logicOnIceMessageReceived(peer, message);
            });
        });
    }

    private void logicOnIceMessageReceived(Peer peer, CandidatesMessage message) {
        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, discarding ice message");
            return;
        }

        log.debug("Got IceMsg for peer, offered candidates: {}", message.getStrCandidates());

        IceState iceState = peer.getIceState();

        if (iceState != NEW && iceState != DISCONNECTED) {
            log.info("Received new candidates/offer, stopping...");
            onConnectionLost(peer);
        }

        createAgent(peer);
        gatherCandidates(peer);

        Agent agent = peer.getAgent();
        IceMediaStream mediaStream = peer.getMediaStream();

        for (Component component : mediaStream.getComponents()) {
            CandidateUtil.unpackCandidates(
                    message,
                    agent,
                    component,
                    mediaStream,
                    peer.isAllowHost(),
                    peer.isAllowReflexive(),
                    peer.isAllowRelay());
        }

        peer.setIceState(CHECKING);
    }
}
