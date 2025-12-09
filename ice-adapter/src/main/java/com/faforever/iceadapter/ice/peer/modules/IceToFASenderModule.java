package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceToFASenderModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_MODULE = "IceToFASenderModule";

    private final Peer peer;

    private boolean running = false;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::startListeners);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopListeners);
    }

    private void startListeners() {
        running = true;
    }

    private void stopListeners() {
        running = false;
    }

    @Override
    public void onIceDataReceived(Peer peer, byte[] data, int offset, int length) {
        if (length == 0) {
            return;
        }

        if (data[0] == 'd') {
            peer.sendToFaSocket(data, offset, length);
        }
    }
}
