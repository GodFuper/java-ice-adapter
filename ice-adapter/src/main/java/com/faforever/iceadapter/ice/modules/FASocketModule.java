package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramSocket;
import java.net.SocketException;

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
        if (socket == null || socket.isClosed()) {
            socket = initForwarding(peer.getPreferredPort());
        }
    }

    private void stopListeners() {
        if (!peer.isClosing()) {
            return;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Starts waiting for data from FA
     */
    private DatagramSocket initForwarding(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            DatagramSocketUtils.resizeBuffer(socket);
            log.debug("Now forwarding data to peer {}", peer.getPeerIdentifier());
            return socket;
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", peer.getPeerIdentifier(), e);
            return null;
        }
    }


}
