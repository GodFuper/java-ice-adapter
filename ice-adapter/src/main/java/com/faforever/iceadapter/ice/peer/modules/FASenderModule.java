package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class FASenderModule implements ModuleBase, PeerEventListener {
    private static final String LOCALHOST = "127.0.0.1";
    private static final String LOCK_FA_SOCKET = "socket_fa";
    private static final String LOCK_MODULE = "FASocketModule";

    private final Peer peer;

    private boolean running = false;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::checkSocket);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopSocket);
    }

    @Override
    public void onSendToFaSocket(Peer peer, byte[] data, int offset, int length) {
        Optional<FASocketModule> socketModule = peer.getModule(PeerModule.FA_SOCKET_MODULE, FASocketModule.class);
        socketModule.map(FASocketModule::getSocket).ifPresent(socket -> {
            lockAndSend(socket, data, offset, length);
        });
    }

    private void lockAndSend(DatagramSocket socket, byte[] data, int offset, int length) {
        LockUtil.executeWithLock(peer.getLock(LOCK_FA_SOCKET), () -> send(socket, data, offset, length));
    }

    private void send(DatagramSocket socket, byte[] data, int offset, int length) {
        try {
            DatagramPacket packet = new DatagramPacket(data, offset, length, InetAddress.getByName(LOCALHOST), peer.getLobbyPort());
            socket.send(packet);
        } catch (UnknownHostException e) {
            // should never happen for 127.0.0.1 but log at debug if it does
            log.debug("UnknownHostException when forwarding to FA for {}", peer.getPeerIdentifier(), e);
        } catch (IOException e) {
            if (peer.isClosing()) {
                log.debug(
                        "Ignoring error while sending packet because the connection was closed {}", peer.getPeerIdentifier());
            } else {
                log.error(
                        "Error while writing to local FA as peer (probably disconnecting from peer) {}",
                        peer.getPeerIdentifier(),
                        e);
                peer.lostConnect();
            }
        }
    }


    private void checkSocket() {
        running = true;
    }

    private void stopSocket() {
        running = false;
    }

}
