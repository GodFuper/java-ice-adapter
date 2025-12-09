package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramSocket;
import java.net.SocketException;

@UtilityClass
@Slf4j
public class DatagramSocketUtils {
    public static final int MAX_SIZE_PACKET = /* assumed MTU */ 1500 - /* IPv4 header */ 20 - /* UDP header */ 8;

    public void resizeBuffer(DatagramSocket socket) {
        try {
            socket.setReceiveBufferSize(MAX_SIZE_PACKET);
            socket.setSendBufferSize(MAX_SIZE_PACKET);
        } catch (SocketException e) {
            if (!socket.isClosed()) {
                log.error("Failed to resize socket buffer on {}", MAX_SIZE_PACKET, e);
            }
        }
    }

}
