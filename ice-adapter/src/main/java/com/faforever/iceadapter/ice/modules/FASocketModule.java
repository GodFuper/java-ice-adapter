package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.util.LockUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramSocket;

@Slf4j
@RequiredArgsConstructor
public class FASocketModule implements FAModule {

    private static final String LOCK_MODULE = "FASocketModule";

    private final Peer peer;
    @Getter
    private DatagramSocket socket;

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::startListeners);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopListeners);
    }

    private void startListeners() {
        if (peer.isClosing()) {
            return;
        }
        if (socket == null || socket.isClosed()) {
            log.error("Socket {} is null or closed", peer.getPeerIdentifier());
        }
    }

    private void stopListeners() {
        if (peer.isClosing() && socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
        }
    }

}
