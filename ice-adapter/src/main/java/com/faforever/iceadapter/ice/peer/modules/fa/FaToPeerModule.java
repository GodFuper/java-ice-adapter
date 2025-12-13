package com.faforever.iceadapter.ice.peer.modules.fa;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.faforever.iceadapter.util.DatagramSocketUtils.MAX_SIZE_PACKET;

@Slf4j
@RequiredArgsConstructor
public class FaToPeerModule implements ModuleBase {
    public static final char COMMAND_FA = 'd';
    private static final String LOCK_MODULE = "FAListenerModule";
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Peer peer;
    private volatile Future<?> listener;

    private volatile boolean isRunning = false;

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::startListeners);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopListeners);
    }

    private void startListeners() {
        if (listener == null ||
                listener.isDone()) {
            listener = executor.submit(this::faListener);
        }
    }

    private void stopListeners() {
        if (peer.isClosing() && listener != null && !listener.isDone()) {
            listener.cancel(true);
            listener = null;
        }
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {

        isRunning = true;
        while (!peer.isClosing()) {
            DatagramSocket socket = peer.getFaSocket();
            if (socket != null) {
                receiveCatch(socket);
            } else {
                log.error("Socket is null. Receive from FA skipped");
                break;
            }
        }
        isRunning = false;
        log.debug("No longer listening for messages from FA for peer");
    }

    private void receiveCatch(DatagramSocket socket) {
        try {
            receive(socket);
        } catch (SocketException se) {
            // socket closed or network error
            if (peer.isClosing()) {
                log.debug("FA listener shutting down for peer: {}", se.toString());
            } else {
                log.warn("SocketException in FA listener for peer: {}", se.toString());
                // Try to trigger ICE reconnect safely
                try {
                    peer.lostConnect();
                } catch (Exception ex) {
                    log.debug("Error while requesting ICE reconnect after socket exception", ex);
                }
            }
        } catch (IOException e) {
            if (peer.isClosing()) {
                log.debug(
                        "Ignoring error while receiving packet because the connection was closed as peer");
            } else {
                log.debug("Error while reading from local FA as peer (probably disconnecting from peer)", e);
                try {
                    peer.lostConnect();
                } catch (Exception ex) {
                    log.debug("Error while requesting ICE reconnect after IO error", ex);
                }
            }
        }
    }

    private void receive(DatagramSocket socket) throws IOException {
        if (!peer.isConnected()) {
            return;
        }
        byte[] data = new byte[MAX_SIZE_PACKET];
        DatagramPacket packet = new DatagramPacket(data, data.length);
        socket.receive(packet);
        if (packet.getLength() == 0) {
            return;
        }
        // Defensive copy of payload to avoid races with the receive buffer
        byte[] copy = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());

        // Forward to ICE - this method will drop packets if ICE isn't ready
        onFaDataReceived(copy);
    }

    /**
     * Data received from FA, prepends prefix and sends it via ICE to the other peer
     *
     * @param faData
     */
    void onFaDataReceived(byte[] faData) {
        int length = faData.length;
        byte[] data = new byte[length + 1];
        data[0] = COMMAND_FA;
        System.arraycopy(faData, 0, data, 1, length);
        peer.sendToPeer(data, 0, data.length);
    }
}
