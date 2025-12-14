package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.io.IOException;
import java.util.concurrent.locks.Lock;

@Slf4j
@RequiredArgsConstructor
public class PeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_COMPONENT = "LockComponent";
    private static final String LOCK_SOCKET = "LockIceSocket";

    private final Peer peer;

    private boolean running = false;

    private Component component;
    private Lock lockComponent;

    @Override
    public void init() {
        peer.addEventListener(this);
        lockComponent = peer.getLock(LOCK_COMPONENT);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(lockComponent, () -> setComponent(null));
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        LockUtil.executeWithLock(lockComponent, () -> setComponent(component));
    }

    private void setComponent(Component component) {
        this.component = component;
        running = component != null;
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data, int offset, int length) {
        Component currentComponent = LockUtil.executeWithLock(lockComponent, () -> this.component);
        if (currentComponent == null) {
            log.warn("Cannot send: component is null");
            return;
        }
        send(currentComponent, data, offset, length);
    }

    private void send(Component component, byte[] data, int offset, int length) {
        if (peer.isClosing()) {
            return;
        }
        try {
            component.send(data, offset, length);
            log.info("Send to {} {} {}", peer.getPeerIdentifier(), offset, length);
        } catch (IOException e) {
            if (!peer.isClosing()) {
                log.error("Send failed", e);
                peer.lostConnect();
            }
        }
    }
}
