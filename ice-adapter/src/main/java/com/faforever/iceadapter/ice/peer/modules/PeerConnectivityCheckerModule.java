package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledFuture;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityCheckerModule implements ModuleBase, PeerEventListener {

    public static final char COMMAND_ECHO = 'e';
    private static final String LOCK_CHECKER_MODULE = "PeerConnectivityCheckerModule";

    private static final int ECHO_INTERVAL = 1000;
    private static final int TIMEOUT_BEFORE_LOST_CONNECT = 10000;
    private final Peer peer;
    private final IceAsync iceAsync;

    @Getter
    private long echosReceived = 0;

    @Getter
    private long invalidEchosReceived = 0;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onIceDataReceived(Peer peer, byte[] data, int offset, int length) {
        if (data.length == 0) {
            return;
        }

        if (data[0] == COMMAND_ECHO) {
            onEchoReceived(data, length);
        }
    }

    private void onEchoReceived(byte[] data, int length) {
        if (!peer.isLocalOffer()) {
            peer.sendToPeer(data, 0, length);// Turn around, send echo back
        }
    }

    @Override
    public void start() {
        if (!peer.isLocalOffer()) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CHECKER_MODULE), () -> {
            if (peer.isClosing()) {
                return;
            }
            if (isRunning()) {
                return;
            }


            log.debug("Starting connectivity checker for peer");

            peer.setLastPacketReceived(System.currentTimeMillis());
            scheduledFuture = iceAsync.scheduleAtFixedRate(peer, this::checkerThread, ECHO_INTERVAL);
        });
    }

    private boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isDone();
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

        peer.sendToPeer(data, 0, data.length);

        long lastPacketReceived = peer.getLastPacketReceived();
        long sinceLastReal = System.currentTimeMillis() - lastPacketReceived;

        if (sinceLastReal > TIMEOUT_BEFORE_LOST_CONNECT) {
            log.warn("{} No traffic (echo or game) for {} ms (> {} ms timeout). Closing connection.",
                    peer.getPeerIdentifier(), lastPacketReceived, TIMEOUT_BEFORE_LOST_CONNECT);
            peer.lostConnect();
            return;
        }
        debug().peerConnectivityUpdate(peer);
    }
}
