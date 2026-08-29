package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * KCP adapter that bridges ice4j Component (DatagramSocket) with MyKcp protocol.
 * <p>
 * Uses separate queues for read and write operations to avoid deadlocks:
 * - WriteTask processes application-level sends (kcp.send())
 * - ReadTask processes incoming packets (kcp.input())
 * - Update loop runs on a dedicated thread and handles kcp.update()
 */
@Slf4j
@Getter
public class KcpAdapter implements KcpTransport {

    private static final int MTU = 1200;
    private static final int SEND_WINDOW = 128;
    private static final int RECV_WINDOW = 256;
    private static final int DEADLINK = 10;
    private static final int UPDATE_INTERVAL_MS = 20;
    private static final int MAX_UPDATE_DELAY_MS = 1000;

    private final String name;
    private final IceKcpOutput output;
    private final Consumer<byte[]> handleData;
    private final IceKcp iceKcp;

    private volatile boolean running = false;
    private volatile long nextUpdateTimestamp = 0;

    private final Queue<ByteBuf> writeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> readQueue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService taskExecutor;

    private ScheduledFuture<?> updateTask;
    private final AtomicBoolean manualRunProcessing = new AtomicBoolean(false);
    private final AtomicBoolean kcpProcessing = new AtomicBoolean(false);

    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);

    @Override
    public long getNextUpdate() {
        return nextUpdateTimestamp;
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public KcpAdapter(int conv, String name, IceKcpOutput output, Consumer<byte[]> handleData) {
        this.name = name;
        this.output = output;
        this.handleData = handleData;

        // Conv = remoteId
        this.iceKcp = new IceKcp(conv, output);

        // Nodelay mode for minimum latency (gaming mode)
        iceKcp.nodelay(true, UPDATE_INTERVAL_MS, 3, true);
        iceKcp.setMtu(MTU);
        iceKcp.wndsize(SEND_WINDOW, RECV_WINDOW);
        iceKcp.setDeadLink(DEADLINK);
    }

    /**
     * Start the KCP update loop.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        taskExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KCP-Update-%s".formatted(name));
            t.setDaemon(true);
            return t;
        });

        updateTask = taskExecutor.scheduleAtFixedRate(
                this::runUpdateLoop,
                0,
                UPDATE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    protected void notifyRunUpdateEvent() {
        if (!running) {
            return;
        }
        if (manualRunProcessing.compareAndSet(false, true)) {
            taskExecutor.execute(this::runUpdateLoop);
        }
    }

    /**
     * Main update loop - runs on dedicated KCP-Update thread.
     */
    private void runUpdateLoop() {
        if (!running) {
            return;
        }

        if (!kcpProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            // Process write queue (kcp.send())
            processWriteQueue();
            // Process read queue (kcp.input())
            processReadQueue();

            // Throttle: only run KCP update if nextUpdateTimestamp has arrived
            if (System.currentTimeMillis() > nextUpdateTimestamp) {
                processUpdate();

                long now = System.currentTimeMillis();
                // Schedule next update
                nextUpdateTimestamp = scheduleNextUpdate((int) now);
            }

            processReceiveAndDeliver();
        } finally {
            kcpProcessing.set(false);
            manualRunProcessing.set(false);
        }
    }

    private void processUpdate() {
        long now = System.currentTimeMillis();
        // Update KCP state
        iceKcp.update((int) now);
    }

    private void processReceiveAndDeliver() {
        // Receive and deliver data
        ByteBuf recvBuf = Unpooled.buffer(iceKcp.getMtu());
        try {
            while (iceKcp.canRecv()) {
                int peekSize = iceKcp.peekSize();
                if (peekSize <= 0) {
                    break;
                }
                recvBuf.clear();
                int received = iceKcp.recv(recvBuf);
                if (received <= 0) {
                    break;
                }
                byte[] data = new byte[recvBuf.readableBytes()];
                recvBuf.getBytes(recvBuf.readerIndex(), data);
                bytesReceived.addAndGet(data.length);
                handleData.accept(data);
            }
        } finally {
            recvBuf.release();
        }
    }

    /**
     * Process write queue: drain application buffers and call kcp.send().
     */
    private void processWriteQueue() {
        ByteBuf buf;

        while ((buf = writeQueue.poll()) != null) {
            try {
                int sent = iceKcp.send(buf);
                if (sent > 0) {
                    buf.release();
                } else {
                    // Queue is full or error - put it back
                    writeQueue.offer(buf);
                    break;
                }
            } catch (Exception e) {
                log.error("KCP send failed for {}", name, e);
                buf.release();
            }
        }
    }

    /**
     * Process read queue: drain incoming packets and call kcp.input().
     */
    private void processReadQueue() {
        byte[] packet;

        while ((packet = readQueue.poll()) != null) {
            try {
                iceKcp.input(Unpooled.wrappedBuffer(packet));
            } catch (Exception e) {
                log.error("KCP input failed for {}", name, e);
            }
        }
    }

    /**
     * Schedule the next update based on KCP internal state.
     */
    private long scheduleNextUpdate(int now) {
        int nextTs = iceKcp.check(now);
        long minNext = now + UPDATE_INTERVAL_MS;
        long maxNext = now + MAX_UPDATE_DELAY_MS;

        // Ensure minimum delay for stability, cap maximum to prevent hang
        if (nextTs < minNext) {
            nextTs = (int) minNext;
        }
        if (nextTs > maxNext) {
            nextTs = (int) maxNext;
        }
        return nextTs;
    }

    /**
     * Send application data through KCP.
     * Queue the buffer for async processing by WriteTask.
     */
    public void send(byte[] payload) {
        bytesSent.addAndGet(payload.length);
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        writeQueue.offer(buf);
        notifyRunUpdateEvent();
    }

    @Override
    public void onReceive(byte[] data, int offset, int length) {
        // Copy the relevant portion to avoid holding references to external buffers
        byte[] packetCopy = new byte[length];
        System.arraycopy(data, offset, packetCopy, 0, length);
        readQueue.offer(packetCopy);
        notifyRunUpdateEvent();
    }

    /**
     * Get the current send buffer size (for diagnostics).
     */
    public int getWaitSnd() {
        return iceKcp.waitSnd();
    }

    /**
     * Get the current KCP state (0 = normal, -1 = dead-link).
     */
    public int getState() {
        return iceKcp.getState();
    }

    public int getConv() {
        return iceKcp.getConv();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Stop the KCP adapter and release resources.
     */
    public void stop() {
        if (taskExecutor != null) {
            running = false;

            if (updateTask != null) {
                updateTask.cancel(false);
            }

            taskExecutor.shutdown();

            try {
                if (!taskExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }

            } catch (InterruptedException e) {
                taskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Drain and release all pending buffers
            ByteBuf buf;
            while ((buf = writeQueue.poll()) != null) {
                buf.release();
            }
        }
    }

    /**
     * Get KCP statistics (for diagnostics).
     */
    public IceKcpMetric getMetric() {
        return iceKcp.getMetric();
    }
}
