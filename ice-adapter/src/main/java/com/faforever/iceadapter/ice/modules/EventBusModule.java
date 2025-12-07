package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RequiredArgsConstructor
public class EventBusModule implements ModuleBase {
    private final CopyOnWriteArrayList<PeerEventListener> listeners = new CopyOnWriteArrayList<>();

    private final Peer peer;

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
        listeners.forEach(l -> safeCall(l, "onIceStateChange",
                () -> l.onIceStateChange(peer, oldState, newState)));
    }

    public void onClose(Peer peer) {
        log.info("Peer closed: {}", peer.getPeerIdentifier());
        listeners.forEach(l -> safeCall(l, "onClose", () -> l.onClose(peer)));
    }

    @Override
    public void start() {

    }

    private void safeCall(PeerEventListener listener, String methodName, Runnable call) {
        try {
            call.run();
        } catch (Exception e) {
            log.error("Exception in EventListener {} during {}", listener.getClass().getSimpleName(), methodName, e);
        }
    }

    @Override
    public void stop() {
        if (!peer.isClosing()) {
            return;
        }

        listeners.clear();
    }
}
