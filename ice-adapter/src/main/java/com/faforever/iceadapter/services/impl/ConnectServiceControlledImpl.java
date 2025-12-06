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
public class ConnectServiceControlledImpl extends ConnectServiceCommon implements ConnectService {

    private final Map<Integer, Peer> waitingCandidates = new ConcurrentHashMap<>();

    public ConnectServiceControlledImpl(IceGameSession iceGameSession, IceAsync iceAsync) {
        super(iceGameSession, iceAsync);
    }

    void onIceStateNew(Peer peer) {
        createAgent(peer);
        peer.setIceState(GATHERING);
    }

    void onIceStateGathering(Peer peer) {
        gatherCandidates(peer);
        peer.setIceState(AWAITING_CANDIDATES);
    }

    void onIceAwaitingCandidates(Peer peer) {
        LockUtil.executeWithLock(lockMessageReceived, () -> waitingCandidates.put(peer.getRemoteId(), peer));

        // Make sure to abort the connection process and reinitiate when we haven't received an answer to our offer in 6
        // seconds, candidate packet was probably lost
        final int currentAwaitingCandidatesEventId = peer.getAwaitingCandidatesEventId().incrementAndGet();
        iceAsync.runAsyncDelay(peer, () -> asyncTimeoutAwaitingCandidates(currentAwaitingCandidatesEventId, peer), 6000);
    }

    void onIceStateDisconnected(Peer peer, IceState oldState) {
        onDisconnected(peer, oldState);
        tryReInitState(peer, oldState);
    }

    void onIceStateCompleted(Peer peer) {
        log.info("ICE state completed");
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

    private void tryReInitState(Peer peer, IceState oldState) {
        if (oldState == CONNECTED) {
            peer.setIceState(NEW);
        } else {
            iceAsync.runAsyncDelay(peer, () -> peer.setIceState(NEW), 5000);
        }
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
        if (peer == null || message == null) {
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
            Peer peerWaiting = waitingCandidates.get(idFrom);
            if (peerWaiting == null) {
                log.error("Peer with id {} wasn't not waitingCandidates", idFrom);
                return;
            }
            atomicBoolean.set(true);
        });

        if (!atomicBoolean.get()) {
            return;
        }

        iceAsync.runAsync(peer, () -> {
            LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
                onGetCandidates(peer, message);
            });
        });
    }

    private void onGetCandidates(Peer peer, CandidatesMessage message) {
        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, discarding ice message");
            return;
        }

        log.debug("Got CandidatesMessage for peer, offered candidates: {}", message.getStrCandidates());

//        #ConnectServiceControlledImpl - is always isLocalOffer == true and logicOnGetCandidates on when iceState == AWAITING_CANDIDATES
//        if (peer.isLocalOffer()) {
//            if (peer.getIceState() != AWAITING_CANDIDATES) {
//                log.warn("Received candidates unexpectedly, current state: {}", peer.getIceState());
//                return;
//            }
//        }

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
