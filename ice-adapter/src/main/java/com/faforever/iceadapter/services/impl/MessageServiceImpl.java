package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.modules.FAModule;
import com.faforever.iceadapter.ice.modules.IceModule;
import com.faforever.iceadapter.services.ConnectService;
import com.faforever.iceadapter.services.MessagesService;
import com.faforever.iceadapter.util.IceUtils;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class MessageServiceImpl implements MessagesService {

    private static final String LOCALHOST = "127.0.0.1";
    private static final String LOCK_FA_SOCKET = "socket_fa";
    private final IceGameSession iceSession;
    private final ConnectService connectService;

    @Override
    public void sendPacketToPeer(Peer peer, byte[] data, int offset, int length) {
        Objects.requireNonNull(peer);
        Optional<Component> activeComponent = IceUtils.getFirstActiveComponent(peer.getMediaStream());

        activeComponent.ifPresent(component -> {
            if (peer.isConnected()) {
                try {
                    DatagramPacket packet = new DatagramPacket(data, offset, length);
                    component.getSocket().send(packet);
                } catch (Exception e) {
                    log.warn("{} Failed send data to peer", peer.getPeerIdentifier(), e);
                }
            }
        });
    }

    @Override
    public void sendPacketToFA(Peer peer, byte[] data, int offset, int length) {
        LockUtil.executeWithLock(peer.getLock(LOCK_FA_SOCKET), () -> {
            // If we're closing or ICE isn't connected yet, drop packets to avoid races.
            if (peer.isClosing()) {
                log.debug("Dropping incoming ICE packet because peer is closing: {}", peer.getPeerIdentifier());
                return;
            }

            // If ICE isn't established, drop early and log at trace level.
            if (!peer.isConnected()) {
                log.trace("Dropping incoming ICE packet because ICE not connected yet: {}", peer.getPeerIdentifier());
                return;
            }

            try {
                DatagramPacket packet = new DatagramPacket(data, offset, length, InetAddress.getByName(LOCALHOST), iceSession.getLobbyPort());
                DatagramSocket socket = peer.getModule(IceModule.FA_SOCKET_MODULE, FAModule.class)
                        .map(FAModule::getSocket).orElse(null);
                if (socket != null) {
                    socket.send(packet);
                }
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
                    // If writing to FA fails repeatedly, request a reconnect to be safe
                    try {
                        connectService.onConnectionLost(peer);
                    } catch (Exception ex) {
                        log.debug("Error while requesting ICE reconnect", ex);
                    }
                }
            }
        });
    }
}
