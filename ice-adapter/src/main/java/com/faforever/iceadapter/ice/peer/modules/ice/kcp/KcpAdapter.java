package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.util.LockUtil;
import io.jpower.kcp.netty.KcpOutput;
import io.jpower.kcp.netty.Ukcp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * KCP adapter that bridges ice4j Component (DatagramSocket) with KCP protocol.
 * <p>
 * Adapts kcp-netty to work on top of ice4j Component instead of Netty DatagramChannel.
 * KCP data is sent/received through the existing ICE component's datagram socket.
 */
@Slf4j
@Getter
public class KcpAdapter {

    private static final int MTU = 1200;
    private static final int SEND_WINDOW = 128;
    private static final int RECV_WINDOW = 256;
    private static final int DEADLINK = 30;
    private static final int UPDATE_INTERVAL_MS = 20;
    private static final int MAX_UPDATE_DELAY_MS = 1000; // Cap to prevent hang when send buffer is empty

    private final String name;
    private final KcpOutput output;
    private final Consumer<byte[]> handleData;
    private final Ukcp ukcp;
    private volatile boolean running = false;
    private volatile long nextUpdateTimestamp = 0;
    private ScheduledExecutorService updateExecutor;
    private final Lock lock = new ReentrantLock();

    public KcpAdapter(int conv, String name, KcpOutput output, Consumer<byte[]> handleData) {
        this.name = name;
        this.output = output;
        this.handleData = handleData;

        // Conv = remoteId
        this.ukcp = new Ukcp(conv, output);

        // Nodelay mode for minimum latency (gaming mode)
        ukcp.nodelay(true, UPDATE_INTERVAL_MS, 2, true);
        ukcp.setMtu(MTU);
        ukcp.wndSize(SEND_WINDOW, RECV_WINDOW);
        ukcp.setDeadLink(DEADLINK);
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
        long now = System.currentTimeMillis();

        // Schedule the next update based on KCP state
        nextUpdateTimestamp = scheduleNextUpdate(now);

        LockUtil.executeWithLock(lock, () -> {
            ukcp.update((int) now);

            // Receive and deliver data
            while (ukcp.peekSize() > 0) {
                int size = ukcp.peekSize();
                if (size <= 0) {
                    break;
                }
                ByteBuf buf = Unpooled.buffer(size);
                try {
                    try {
                        ukcp.receive(buf);
                    } catch (IOException e) {
                        log.error("KCP receive failed for {}", name, e);
                        break;
                    }

                    byte[] data = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), data);
                    handleData.accept(data);
                } finally {
                    buf.release();
                }
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
        try {
            ukcp.input(Unpooled.wrappedBuffer(data, offset, length));
        } catch (IOException e) {
            log.error("KCP input failed for {}", name, e);
        }
    }

    /**
     * Send application data through KCP.
     */
    public void send(byte[] payload) {
        try {
            ukcp.send(Unpooled.wrappedBuffer(payload));
        } catch (IOException e) {
            log.error("KCP send failed for {}", name, e);
        }
    }

    /**
     * Get the current send buffer size (for diagnostics).
     */
    public int getWaitSnd() {
        return ukcp.waitSnd();
    }

    /**
     * Determine when to call update() next, based on KCP internal state.
     * Returns the next timestamp in milliseconds.
     * Ensures at least UPDATE_INTERVAL_MS and at most MAX_UPDATE_DELAY_MS gap between calls.
     * Capping prevents scheduling extremely far in the future when send buffer is empty
     * (ukcp.check() returns Integer.MAX_VALUE for tmPacket in that case).
     */
    private long scheduleNextUpdate(long now) {
        long nextTs = ukcp.check((int) now);
        // Use check() result as minimum, but cap to prevent extreme delays
        long minNext = now + UPDATE_INTERVAL_MS;
        long maxNext = now + MAX_UPDATE_DELAY_MS;
        if (nextTs < minNext) {
            nextTs = minNext;
        } else if (nextTs > maxNext) {
            nextTs = maxNext;
        }
        nextUpdateTimestamp = nextTs;
        return nextUpdateTimestamp;
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
        }
    }

}
