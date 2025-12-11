package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class PeerToPeerModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_MODULE = "PeerToPeerModule";

    private final Peer peer;

    private boolean running = false;

    private Component component;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), () -> setComponent(null));
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), () -> setComponent(component));
    }

    private void setComponent(Component component) {
        this.component = component;
        running = component != null;
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data, int offset, int length) {
        if (peer.isClosing()) {
            return;
        }
        if (component == null) {
            log.error("component is null. Send is skipped");
            return;
        }
        send(component, data, offset, length);
    }

    private void send(Component component, byte[] data, int offset, int length) {
        try {
            component.send(data, offset, length);
        } catch (IOException e) {
            if (!peer.isClosing()) {
                log.error("Send failed", e);
                peer.lostConnect();
            }
        }
    }
}
