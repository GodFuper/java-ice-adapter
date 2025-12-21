package com.faforever.iceadapter.ice.peer.modules.ice;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.fa.FaToPeerModule;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;
import org.ice4j.socket.MultiplexingDatagramSocket;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;

import static com.faforever.iceadapter.ice.peer.modules.other.PeerConnectivityCheckerModule.COMMAND_ECHO;

@Slf4j
@RequiredArgsConstructor
public class PeerToPeerListenerModule implements ModuleBase, PeerEventListener {
    private static final String LOCK_SOCKET = "PeerToPeerSocket";

    private final Peer peer;
    private volatile boolean running = false;
    private Lock lockSocket;

    @Override
    public void init() {
        peer.addEventListener(this);
        lockSocket = peer.getLock(LOCK_SOCKET);
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        if (component == null) {
            stop();
            return;
        }

        log.info("Create ice listener");
        Thread.ofVirtual().name(threadComponentListenerName()).start(() -> LockUtil.executeWithLock(lockSocket, () -> createListener(component)));
    }

    private String threadComponentListenerName() {
        return "ComponentListener-%s".formatted(peer.getPeerIdentifier());
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
                log.trace("Receive from {} {}", peer.getPeerIdentifier(), packet.getLength());
                if (packet.getLength() == 0) {
                    return;
                }

                // We copy the buffer so that we can reuse it next time.
                byte[] dataCopy = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), dataCopy, 0, packet.getLength());

                Thread.ofVirtual()
                        .name(threadComponentListenerName())
                        .start(() -> handlerData(peer, dataCopy, 0, dataCopy.length));
            } catch (Exception e) {
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


        if (data[0] == FaToPeerModule.COMMAND_FA) {
            peer.sendToFaSocket(data, 1, length - 1);
        } else if (data[0] == COMMAND_ECHO) {
            if (!peer.isLocalOffer()) {
                peer.sendToPeer(data, offset, length);
            }
            if (length == 9) {
                peer.setLastEcho(Longs.fromByteArray(Arrays.copyOfRange(data, 1, length)));
                peer.getEchosReceived().incrementAndGet();
            } else {
                peer.getInvalidPacket().incrementAndGet();
                log.error("Invalid Echo received. length={}", length);
            }

        } else if (DatagramSocketUtils.isStunPacket(data, length)) {
            int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            log.debug("STUN-like packet received, type: 0x{}, length: {}", String.format("%04X", type), length);
        } else {
            peer.getInvalidPacket().incrementAndGet();
            log.warn("Received invalid packet, first byte: 0x{}, length: {}, data (hex): {}",
                    String.format("%02X", data[0]),
                    length,
                    DatagramSocketUtils.bytesToHex(Arrays.copyOf(data, Math.min(length, 16)))
            );
        }
    }

}
