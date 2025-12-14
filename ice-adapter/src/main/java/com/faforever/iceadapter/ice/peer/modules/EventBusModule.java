package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RequiredArgsConstructor
public class EventBusModule implements ModuleBase, PeerEventListener {
    private final CopyOnWriteArrayList<PeerEventListener> listeners = new CopyOnWriteArrayList<>();

    private final Peer peer;
    private final IceAsync iceAsync;

    public void register(PeerEventListener listener) {
        listeners.add(listener);
        log.debug("EventListener registered: {}", listener.getClass().getSimpleName());
    }

    public void unregister(PeerEventListener listener) {
        listeners.remove(listener);
        log.debug("EventListener unregistered: {}", listener.getClass().getSimpleName());
    }

    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        log.info("Peer {} change iceState {} -> {}", peer.getPeerIdentifier(), oldState, newState);
        listeners.forEach(l -> safeAsyncCall(l, "onIceStateChange",
                () -> l.onIceStateChange(peer, oldState, newState)));
    }

    public void onAgentChange(Peer peer, Agent newAgent) {
        log.info("Peer {} agent changed. now = {}", peer.getPeerIdentifier(), newAgent);
        listeners.forEach(l -> safeAsyncCall(l, "onAgentChange",
                () -> l.onAgentChange(peer, newAgent)));
    }

    public void onIceMediaStreamChange(Peer peer, IceMediaStream stream) {
        log.info("Peer {} iceMediaStream changed. now = {}", peer.getPeerIdentifier(), stream);
        listeners.forEach(l -> safeAsyncCall(l, "onIceMediaStreamChange",
                () -> l.onIceMediaStreamChange(peer, stream)));
    }

    public void onIceComponentChange(Peer peer, Component component) {
        log.info("Peer {} component changed. now = {}", peer.getPeerIdentifier(), component);
        listeners.forEach(l -> safeAsyncCall(l, "onIceComponentChange",
                () -> l.onIceComponentChange(peer, component)));
    }

    @Override
    public void onChangeEcho(Peer peer, Long lastEcho, long echo) {
        listeners.forEach(l -> safeAsyncCall(l, "onChangeEcho",
                () -> l.onChangeEcho(peer, lastEcho, echo)));
    }

    public void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
        log.trace("Peer {} change lastPacketReceived {} -> {}", peer.getPeerIdentifier(), lastTimestamp, timestamp);
        listeners.forEach(l -> safeAsyncCall(l, "onLastPacketReceived",
                () -> l.onLastPacketReceived(peer, lastTimestamp, timestamp)));
    }

    @Override
    public void onSendToFaSocket(Peer peer, byte[] data, int offset, int length) {
        log.trace("Peer {} onSendToFaSocket data with length {}", peer.getPeerIdentifier(), length);
        listeners.forEach(l -> safeAsyncCall(l, "onSendToFaSocket",
                () -> l.onSendToFaSocket(peer, data, offset, length)));
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data, int offset, int length) {
        log.trace("Peer {} onSendToPeer data with length {}", peer.getPeerIdentifier(), length);
        listeners.forEach(l -> safeAsyncCall(l, "onSendToPeer",
                () -> l.onSendToPeer(peer, data, offset, length)));
    }

    @Override
    public void onConnectionLost(Peer peer) {
        log.info("Peer onConnectionLost: {}", peer.getPeerIdentifier());
        listeners.forEach(l -> safeAsyncCall(l, "onConnectionLost", () -> l.onConnectionLost(peer)));
    }

    @Override
    public void onClose(Peer peer, boolean hasClosed) {
        log.info("Peer closed: {}", peer.getPeerIdentifier());
        listeners.forEach(l -> safeAsyncCall(l, "onClose", () -> l.onClose(peer, hasClosed)));
    }

    private void safeAsyncCall(PeerEventListener listener, String methodName, Runnable call) {
        asyncVirtual(methodName, peer, call);
    }

    private void asyncVirtual(String methodName, Peer peer, Runnable call) {

        Thread.ofVirtual()
                .name(methodName + "|" + peer.getPeerIdentifier())
                .start(call);
    }

    @Override
    public void stop() {
        if (!peer.isClosing()) {
            return;
        }

        listeners.clear();
    }
}
