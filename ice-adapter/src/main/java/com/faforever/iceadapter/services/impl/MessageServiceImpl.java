package com.faforever.iceadapter.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MessageServiceImpl {

//    private static final String LOCALHOST = "127.0.0.1";
//    private static final String LOCK_FA_SOCKET = "socket_fa";
//    private final IceGameSession iceSession;
//    private final ConnectService connectService;
//
//    public void sendPacketToPeer(Peer peer, byte[] data, int offset, int length) {
//        Objects.requireNonNull(peer);
//        Optional<Component> activeComponent = IceUtils.getFirstActiveComponent(peer.getMediaStream());
//
//        activeComponent.ifPresent(component -> {
//            try {
//                DatagramPacket packet = new DatagramPacket(data, offset, length);
//                component.getSocket().send(packet);
//            } catch (Exception e) {
//                log.warn("{} Failed send data to peer", peer.getPeerIdentifier(), e);
//            }
//        });
//    }
//
//    public void sendPacketToFA(Peer peer, byte[] data, int offset, int length) {
//        LockUtil.executeWithLock(peer.getLock(LOCK_FA_SOCKET), () -> {
//            // If we're closing or ICE isn't connected yet, drop packets to avoid races.
//            if (peer.isClosing()) {
//                log.debug("Dropping incoming ICE packet because peer is closing: {}", peer.getPeerIdentifier());
//                return;
//            }
//
//            // If ICE isn't established, drop early and log at trace level.
//            if (!peer.isConnected()) {
//                log.trace("Dropping incoming ICE packet because ICE not connected yet: {}", peer.getPeerIdentifier());
//                return;
//            }
//
//            try {
//                DatagramPacket packet = new DatagramPacket(data, offset, length, InetAddress.getByName(LOCALHOST), iceSession.getLobbyPort());
//                peer.getFaSocket().send(packet);
//            } catch (UnknownHostException e) {
//                // should never happen for 127.0.0.1 but log at debug if it does
//                log.debug("UnknownHostException when forwarding to FA for {}", peer.getPeerIdentifier(), e);
//            } catch (IOException e) {
//                if (peer.isClosing()) {
//                    log.debug(
//                            "Ignoring error while sending packet because the connection was closed {}", peer.getPeerIdentifier());
//                } else {
//                    log.error(
//                            "Error while writing to local FA as peer (probably disconnecting from peer) {}",
//                            peer.getPeerIdentifier(),
//                            e);
//                    // If writing to FA fails repeatedly, request a reconnect to be safe
//                    try {
//                        connectService.onConnectionLost(peer);
//                    } catch (Exception ex) {
//                        log.debug("Error while requesting ICE reconnect", ex);
//                    }
//                }
//            }
//        });
//    }
}
