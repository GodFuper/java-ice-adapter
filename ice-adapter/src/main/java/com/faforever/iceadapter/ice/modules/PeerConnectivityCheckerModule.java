package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.ConnectivityModule;
import com.faforever.iceadapter.ice.IcePeerAdapter;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityCheckerModule implements ConnectivityModule {

    private static final String LOCK_CHECKER_MODULE = "PeerConnectivityCheckerModule";

    private static final int ECHO_INTERVAL = 1000;
    private static final int TIMEOUT_BEFORE_LOST_CONNECT = 10000;

    private final IcePeerAdapter icePeerAdapter;
    private final Peer peer;

    @Getter
    private long echosReceived = 0;

    @Getter
    private long invalidEchosReceived = 0;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void onReceivePacket() {
        peer.setLastPacketReceived(System.currentTimeMillis());
        debug().peerConnectivityUpdate(peer);
    }

    private void calculateRtt(long timeSentEcho) {
        long rttMs = System.currentTimeMillis() - timeSentEcho;
        int rtt = (int) (rttMs);

        float oldRtt = peer.getRtt();
        float calcRtt = oldRtt == 0 ? rtt : oldRtt * 0.8f + (float) rtt * 0.2f;
        peer.setRtt(calcRtt);
    }

    @Override
    public void onEchoReceived(byte[] data, int length) {
        if (!peer.isLocalOffer()) {
            icePeerAdapter.sendPacketToPeer(peer, data, 0, length); // Turn around, send echo back
            return;
        }

        echosReceived++;

        if (length != 9) {
            log.trace("Received echo of wrong length, length: {}", length);
            invalidEchosReceived++;
        }

        long sentMs = Longs.fromByteArray(Arrays.copyOfRange(data, 1, length));
        calculateRtt(sentMs);

        debug().peerConnectivityUpdate(peer);
    }

    @Override
    public void start() {
        if (!peer.isLocalOffer()) {
            return;
        }

        LockUtil.executeWithLock(peer.getLock(LOCK_CHECKER_MODULE), () -> {
            if (isRunning() && !peer.isClosing()) {
                return;
            }

            log.debug("Starting connectivity checker for peer");

            peer.setRtt(0.0f);
            peer.setLastPacketReceived(System.currentTimeMillis());

            scheduledFuture = ExecutorHolder.getScheduledExecutor()
                    .scheduleAtFixedRate(this::checkerThread, 0, ECHO_INTERVAL, TimeUnit.MILLISECONDS);
        });
    }

    private boolean isRunning() {
        return scheduledFuture != null;
    }

    private String getThreadName() {
        return "connectivityChecker-%s".formatted(peer.getPeerIdentifier());
    }

    @Override
    public void stop() {
        if (!peer.isLocalOffer()) {
            return;
        }

        LockUtil.executeWithLock(peer.getLock(LOCK_CHECKER_MODULE), () -> {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
                scheduledFuture = null;
            }
        });
    }

    private void checkerThread() {
        Thread.currentThread().setName(getThreadName());
        log.trace("Running connectivity checker");

        byte[] data = new byte[9];
        data[0] = COMMAND_ECHO;

        // Copy current time (long, 8 bytes) into array after leading prefix indicating echo
        System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 1, 8);

        icePeerAdapter.sendPacketToPeer(peer, data, 0, 9);

        long lastPacketReceived = peer.getLastPacketReceived();
        long sinceLastReal = System.currentTimeMillis() - lastPacketReceived;

        if (sinceLastReal > TIMEOUT_BEFORE_LOST_CONNECT) {
            log.warn("{} No traffic (echo or game) for {} ms (> {} ms timeout). Closing connection.",
                    peer.getPeerIdentifier(), lastPacketReceived, TIMEOUT_BEFORE_LOST_CONNECT);
            icePeerAdapter.onConnectionLost(peer);
            return;
        }
        debug().peerConnectivityUpdate(peer);
    }
}
