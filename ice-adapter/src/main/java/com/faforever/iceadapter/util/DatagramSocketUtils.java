package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramSocket;
import java.net.SocketException;

@UtilityClass
@Slf4j
public class DatagramSocketUtils {
    // 64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
    public static final int MAX_SIZE_PACKET = 1200;

    public void resizeBuffer(DatagramSocket socket) {
        try {
            socket.setReceiveBufferSize(MAX_SIZE_PACKET);
            socket.setSendBufferSize(MAX_SIZE_PACKET);
        } catch (SocketException e) {
            log.error("Failed to resize socket buffer on {}", MAX_SIZE_PACKET, e);
        }
    }

}
