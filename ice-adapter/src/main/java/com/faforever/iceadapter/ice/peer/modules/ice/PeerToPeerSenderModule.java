package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.util.concurrent.locks.Lock;

@Slf4j
@RequiredArgsConstructor
public class PeerToPeerSenderModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_COMPONENT = "LockComponent";

    private final Peer peer;

    @Setter
    private Component component;
    private Lock lockComponent;

    @Override
    public void init() {
        peer.addEventListener(this);
        lockComponent = peer.getLock(LOCK_COMPONENT);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(lockComponent, () -> setComponent(null));
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        LockUtil.executeWithLock(lockComponent, () -> setComponent(component));
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
            log.trace("Send to {} {} {}", peer.getPeerIdentifier(), offset, length);
        } catch (Exception e) {
            if (!peer.isClosing()) {
                log.error("Send failed", e);
                peer.lostConnect();
            }
        }
    }
}
