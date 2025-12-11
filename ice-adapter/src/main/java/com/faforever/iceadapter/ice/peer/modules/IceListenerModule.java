package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ConnectivityModule;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;
import org.ice4j.socket.MultiplexingDatagramSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
public class IceListenerModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_MODULE = "IceListenerModule";

    private final Peer peer;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = false;

    @Override
    public void init() {
        peer.addEventListener(this);
    }


    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        if (component == null) {
            stop();
            return;
        }

        log.info("Create ice listener");
        executor.submit(() -> createListener(component));
    }

    public void stop() {
        if (running) {
            log.info("Stopping IceListenerModule");
            running = false;
            // Прерываем receive() через закрытие сокета или thread.interrupt()
            Component component = peer.getComponent(); // предположим, есть такой метод
            if (component != null) {
                try {
                    component.getSocket().close();
                } catch (Exception e) {
                    log.debug("Error closing socket during stop", e);
                }
            }
        }
    }


    private void createListener(Component component) {
        running = true;
        MultiplexingDatagramSocket datagramSocket = component.getSocket();
        byte[] buf = new byte[DatagramSocketUtils.MAX_SIZE_PACKET];
        while (!datagramSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                datagramSocket.receive(packet);
                handlerData(peer, packet.getData(), packet.getOffset(), packet.getLength());
            } catch (IOException e) {
                if (peer.isClosing()) {
                    break;
                }
                if (!datagramSocket.isClosed()) {
                    log.error("Ice Listener error", e);
                }
                peer.lostConnect();
                break;
            }
        }
        log.info("Ice Listener closed");
        running = false;
    }

    private void handlerData(Peer peer, byte[] data, int offset, int length) {

        peer.setLastPacketReceived(System.currentTimeMillis());

        peer.iceDataReceived(data, offset, length);

        if (data.length > 0 && data[0] != FaToPeerModule.COMMAND_FA && data[0] != ConnectivityModule.COMMAND_ECHO) {
            log.warn("Received invalid packet, first byte: 0x{}, length: {}", data[0], length);
        }
    }

}
