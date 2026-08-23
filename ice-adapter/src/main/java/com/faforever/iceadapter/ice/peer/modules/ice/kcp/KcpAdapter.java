package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.util.LockUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.Kcp;
import kcp.KcpOutput;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * KCP adapter that bridges ice4j Component (DatagramSocket) with KCP protocol.
 * <p>
 * Adapts kcp-base to work on top of ice4j Component instead of Netty DatagramChannel.
 * KCP data is sent/received through the existing ICE component's datagram socket.
 */
@Slf4j
@Getter
public class KcpAdapter {

    private static final int MTU = 1200;
    private static final int SEND_WINDOW = 1024;
    private static final int RECV_WINDOW = 2048;
    private static final int DEADLINK = 30;
    private static final int UPDATE_INTERVAL_MS = 2;
    private static final int MAX_UPDATE_DELAY_MS = 1000;

    private final String name;
    private final KcpOutput output;
    private final Consumer<byte[]> handleData;
    private final Kcp kcp;
    private volatile boolean running = false;
    private volatile long nextUpdateTimestamp = 0;
    private ScheduledExecutorService updateExecutor;
    private final Lock lock = new ReentrantLock();

    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public KcpAdapter(int conv, String name, KcpOutput output, Consumer<byte[]> handleData) {
        this.name = name;
        this.output = output;
        this.handleData = handleData;

        // Conv = remoteId
        this.kcp = new Kcp(conv, output);

        // Nodelay mode for minimum latency (gaming mode)
        kcp.nodelay(true, UPDATE_INTERVAL_MS, 2, true);
        kcp.setMtu(MTU);
        kcp.setSndWnd(SEND_WINDOW);
        kcp.setRcvWnd(RECV_WINDOW);
        // setDeadLink is not available in kcp-base, dead-link detection uses default threshold
    }

    /**
     * Start the KCP update loop.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        updateExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KCP-Update-%s".formatted(name));
            t.setDaemon(true);
            return t;
        });

        updateExecutor.execute(this::runUpdateLoop);
    }

    /**
     * Main loop entry point. Executes one update cycle then schedules the next.
     */
    private void runUpdateLoop() {
        if (!running) {
            return;
        }

        update();

        long delay = Math.max(0, nextUpdateTimestamp - System.currentTimeMillis());
        updateExecutor.schedule(this::runUpdateLoop, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Run KCP update and process incoming data.
     */
    public void update() {
        if (!running) {
            return;
        }

        LockUtil.executeWithLock(lock, () -> {
            long now = System.currentTimeMillis();

            // Schedule the next update based on KCP state
            nextUpdateTimestamp = scheduleNextUpdate(now);

            kcp.update(now);

            // Receive and deliver data
            while (kcp.peekSize() > 0) {
                int size = kcp.peekSize();
                if (size <= 0) {
                    break;
                }
                ByteBuf buf = kcp.mergeRecv();
                if (buf == null) {
                    break;
                }
                byte[] data = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), data);
                buf.release();
                bytesReceived.addAndGet(data.length);
                handleData.accept(data);
            }
        });
    }

    /**
     * Process an incoming raw packet from the peer.
     * This is called by the transport module when data is received.
     */
    public void onIncomingPacket(byte[] data, int offset, int length) {
        if (!running) {
            return;
        }
        LockUtil.executeWithLock(lock, () -> {
            try {
                kcp.input(Unpooled.wrappedBuffer(data, offset, length), true, System.currentTimeMillis());
            } catch (Exception e) {
                log.error("KCP input failed for {}", name, e);
            }
        });

    }

    public Kcp getKcp() {
        if (output instanceof PeerKcpOutput peerOutput) {
            return peerOutput.getKcp()
                    .orElse(null);
        }
        return null;
    }

    /**
     * Send application data through KCP.
     */
    public void send(byte[] payload) {
        bytesSent.addAndGet(payload.length);
        LockUtil.executeWithLock(lock, () -> {
            int r = kcp.send(Unpooled.wrappedBuffer(payload));
            if (r != 0) {
                log.error("KCP send failed for {}, ret={}", name, r);
            }
            return r;
        });
    }

    /**
     * Get the current send buffer size (for diagnostics).
     */
    public int getWaitSnd() {
        return kcp.waitSnd();
    }

    /**
     * Get the current KCP state (0 = normal, -1 = dead-link).
     */
    public int getState() {
        return kcp.getState();
    }

    public int getConv() {
        return kcp.getConv();
    }

    /**
     * Determine when to call update() next, based on KCP internal state.
     * Returns the next timestamp in milliseconds.
     * Ensures at least UPDATE_INTERVAL_MS between updates to maintain stability.
     * Caps maximum delay to prevent hang when send buffer is empty
     * (kcp.check() returns Long.MAX_VALUE for tmPacket in that case).
     */
    private long scheduleNextUpdate(long now) {
        long nextTs = kcp.check(now);
        long minNext = now + UPDATE_INTERVAL_MS;
        long maxNext = now + MAX_UPDATE_DELAY_MS;

        // Ensure minimum delay for stability, cap maximum to prevent hang
        if (nextTs < minNext) {
            nextTs = minNext;
        }
        if (nextTs > maxNext) {
            nextTs = maxNext;
        }
        nextUpdateTimestamp = nextTs;
        return nextTs;
    }

    /**
     * Stop the KCP adapter and release resources.
     */
    public void stop() {
        if (updateExecutor != null) {
            running = false;
            updateExecutor.shutdown();
            try {
                if (!updateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    updateExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                updateExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (output instanceof PeerKcpOutput peerOutput) {
                peerOutput.close();
            }
        }
    }

}
