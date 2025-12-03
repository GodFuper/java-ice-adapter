package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityCheckerModule {

    private static final int ECHO_INTERVAL = 1000;

    private final PeerIceModule ice;
    private final Lock lockIce = new ReentrantLock();
    private volatile boolean running = false;
    private volatile Thread checkerThread;

    @Getter
    private float averageRTT = 0.0f;

    @Getter
    private volatile Long lastPacketReceived;

    @Getter
    private long echosReceived = 0;

    @Getter
    private long invalidEchosReceived = 0;

    void start() {
        LockUtil.executeWithLock(lockIce, () -> {
            if (running) {
                return;
            }

            running = true;
            log.debug("Starting connectivity checker for peer");

            averageRTT = 0.0f;
            lastPacketReceived = System.currentTimeMillis();

            checkerThread = Thread.ofVirtual()
                    .name(getThreadName())
                    .uncaughtExceptionHandler((t, e) -> log.error("Thread {} crashed unexpectedly", t.getName(), e))
                    .start(this::checkerThread);
        });
    }

    private String getThreadName() {
        return "connectivityChecker-%s".formatted(ice.getPeer().getPeerIdentifier());
    }

    void stop() {
        LockUtil.executeWithLock(lockIce, () -> {
            if (!running) {
                return;
            }

            running = false;

            if (checkerThread != null) {
                checkerThread.interrupt();
                checkerThread = null;
            }
        });
    }

    /**
     * an echo has been received, RTT and last_received will be updated
     *
     * @param data
     * @param offset
     * @param length
     */
    void echoReceived(byte[] data, int offset, int length) {
        echosReceived++;

        if (length != 9) {
            log.trace("Received echo of wrong length, length: {}", length);
            invalidEchosReceived++;
        }

        long sentMs = Longs.fromByteArray(Arrays.copyOfRange(data, offset + 1, length));
        long rttMs = System.currentTimeMillis() - sentMs;
        int rtt = (int) (rttMs);

        if (averageRTT == 0) {
            averageRTT = rtt;
        } else {
            averageRTT = averageRTT * 0.8f + (float) rtt * 0.2f;
        }

        debug().peerConnectivityUpdate(ice.getPeer());
    }

    private void checkerThread() {
        while (!Thread.currentThread().isInterrupted() && running) {
            log.trace("Running connectivity checker");

            Peer peer = ice.getPeer();
            byte[] data = new byte[9];
            data[0] = 'e';

            // Copy current time (long, 8 bytes) into array after leading prefix indicating echo
            System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 1, 8);

            ice.sendViaIce(data, 0, data.length);

            debug().peerConnectivityUpdate(peer);
            try {
                Thread.sleep(ECHO_INTERVAL);
            } catch (InterruptedException e) {
                log.warn("{} (sleeping checkerThread) was interrupted", Thread.currentThread().getName());
                return;
            }

            long sinceLastReal = System.currentTimeMillis() - lastPacketReceived;

            if (sinceLastReal > 10_000) {
                log.warn("No traffic (echo or game) from {} for {} ms (> 10000 ms timeout). Closing connection.",
                        peer.getRemoteLogin(), lastPacketReceived);
                CompletableFuture.runAsync(ice::onConnectionLost, IceAdapter.getExecutor());
                return;
            }
            debug().peerConnectivityUpdate(peer);
        }

        log.info("{} stopped gracefully", Thread.currentThread().getName());
    }

    public void notifyTrafficReceived() {
        lastPacketReceived = System.currentTimeMillis();
        debug().peerConnectivityUpdate(ice.getPeer());
    }
}
