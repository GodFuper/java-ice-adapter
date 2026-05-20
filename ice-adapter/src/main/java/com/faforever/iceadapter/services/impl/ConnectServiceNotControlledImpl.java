package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.LockUtil;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

import static com.faforever.iceadapter.ice.IceState.*;

@Slf4j
public class ConnectServiceNotControlledImpl extends ConnectServiceCommon implements ConnectService {

    public ConnectServiceNotControlledImpl(MessageService messageService, IceGameSession iceGameSession, IceAsync iceAsync) {
        super(messageService, iceGameSession, iceAsync);
    }

    void onIceStateNew(Peer peer) {
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
        onDisconnected(peer, oldState);
    }

    void onIceStateChecking(Peer peer) {
        boolean connected = checking(peer);
        if (connected) {
            peer.setIceState(CONNECTED);
        } else {
            connectLost(peer, true);
        }
    }

    void onIceStateConnected(Peer peer) {
        onConnected(peer);
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (peer == null) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> connectLost(peer, false, clearIceState));
    }

    @Override
    public void onMessageFromRPC(Peer peer, CandidatesMessage message) {
        LockUtil.tryExecuteWithLock(peer.getLock(LOCK_CONNECT), () -> {
            logicOnIceMessageReceived(peer, message);
        });
    }

    private void logicOnIceMessageReceived(Peer peer, CandidatesMessage message) {
        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, discarding ice message");
            return;
        }

        log.debug("Got IceMsg for peer, offered candidates: {}", message.toStrCandidates());

        IceState iceState = peer.getIceState();

        if (iceState != NEW && iceState != DISCONNECTED) {
            peer.setIceStateWithoutTrigger(DISCONNECTED);
            log.info("Restarting the connection...");
            onDisconnected(peer, iceState);
        }

        createAgent(peer);
        gatherCandidates(peer);

        Agent agent = peer.getAgent();
        IceMediaStream mediaStream = peer.getMediaStream();

        for (Component component : mediaStream.getComponents()) {
            CandidateUtil.unpackCandidates(
                    peer,
                    message,
                    agent,
                    component,
                    mediaStream,
                    true,
                    true,
                    true);
        }

        onIceStateChecking(peer);
    }

}
