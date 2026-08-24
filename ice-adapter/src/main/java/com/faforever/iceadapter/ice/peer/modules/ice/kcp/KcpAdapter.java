package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcp;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpMetric;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpOutput;
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
public class KcpAdapter {

    private static final int MTU = 1200;
    private static final int SEND_WINDOW = 128;
    private static final int RECV_WINDOW = 256;
    private static final int DEADLINK = 30;
    private static final int UPDATE_INTERVAL_MS = 2;
    private static final int MAX_UPDATE_DELAY_MS = 1000;

    private final String name;
    private final MyKcpOutput output;
    private final Consumer<byte[]> handleData;
    private final MyKcp myKcp;

    private volatile boolean running = false;
    private volatile long nextUpdateTimestamp = 0;

    private final Queue<ByteBuf> writeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> readQueue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService updateExecutor;
    private ScheduledExecutorService taskExecutor;
    private ScheduledFuture<?> updateTask;
    private final AtomicBoolean writeProcessing = new AtomicBoolean(false);
    private final AtomicBoolean readProcessing = new AtomicBoolean(false);

    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public KcpAdapter(int conv, String name, MyKcpOutput output, Consumer<byte[]> handleData) {
        this.name = name;
        this.output = output;
        this.handleData = handleData;

        // Conv = remoteId
        this.myKcp = new MyKcp(conv, output);

        // Nodelay mode for minimum latency (gaming mode)
        myKcp.nodelay(true, UPDATE_INTERVAL_MS, 3, true);
        myKcp.setMtu(MTU);
        myKcp.wndsize(SEND_WINDOW, RECV_WINDOW);
        myKcp.setDeadLink(DEADLINK);
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
        taskExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KCP-Task-%s".formatted(name));
            t.setDaemon(true);
            return t;
        });

        updateTask = updateExecutor.scheduleAtFixedRate(
                this::runUpdateLoop,
                0,
                UPDATE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Main update loop - runs on dedicated KCP-Update thread.
     */
    private void runUpdateLoop() {
        if (!running) {
            return;
        }

        long now = System.currentTimeMillis();

        // Process write queue (kcp.send())
        if (!writeProcessing.compareAndSet(false, true)) {
            return;
        }
        try {
            processWriteQueue();
        } finally {
            writeProcessing.set(false);
        }

        // Process read queue (kcp.input())
        if (!readProcessing.compareAndSet(false, true)) {
            return;
        }
        try {
            processReadQueue();
        } finally {
            readProcessing.set(false);
        }

        // Update KCP state
        myKcp.update((int) now);

        // Receive and deliver data
        ByteBuf recvBuf = Unpooled.buffer(myKcp.getMtu());
        try {
            while (myKcp.canRecv()) {
                int peekSize = myKcp.peekSize();
                if (peekSize <= 0) {
                    break;
                }
                recvBuf.clear();
                int received = myKcp.recv(recvBuf);
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

        // Schedule next update
        nextUpdateTimestamp = scheduleNextUpdate((int) now);
    }

    /**
     * Process write queue: drain application buffers and call kcp.send().
     */
    private void processWriteQueue() {
        ByteBuf buf;
        int writeCount = 0;
        long writeBytes = 0;

        while ((buf = writeQueue.poll()) != null) {
            writeCount++;
            try {
                int sent = myKcp.send(buf);
                if (sent > 0) {
                    writeBytes += sent;
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

        if (writeCount > 0) {
            bytesSent.addAndGet(writeBytes);
        }
    }

    /**
     * Process read queue: drain incoming packets and call kcp.input().
     */
    private void processReadQueue() {
        byte[] packet;
        int readCount = 0;

        while ((packet = readQueue.poll()) != null) {
            readCount++;
            try {
                myKcp.input(Unpooled.wrappedBuffer(packet));
            } catch (Exception e) {
                log.error("KCP input failed for {}", name, e);
            }
        }
    }

    /**
     * Schedule the next update based on KCP internal state.
     */
    private long scheduleNextUpdate(int now) {
        int nextTs = myKcp.check(now);
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
        if (!running) {
            return;
        }
        bytesSent.addAndGet(payload.length);
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        writeQueue.offer(buf);
    }

    /**
     * Process an incoming raw packet from the peer.
     * Queue the packet for async processing by ReadTask.
     */
    public void onIncomingPacket(byte[] data, int offset, int length) {
        if (!running) {
            return;
        }

        // Copy the relevant portion to avoid holding references to external buffers
        byte[] packetCopy = new byte[length];
        System.arraycopy(data, offset, packetCopy, 0, length);
        readQueue.offer(packetCopy);
    }

    /**
     * Get the current send buffer size (for diagnostics).
     */
    public int getWaitSnd() {
        return myKcp.waitSnd();
    }

    /**
     * Get the current KCP state (0 = normal, -1 = dead-link).
     */
    public int getState() {
        return myKcp.getState();
    }

    public int getConv() {
        return myKcp.getConv();
    }

    /**
     * Stop the KCP adapter and release resources.
     */
    public void stop() {
        if (updateExecutor != null && taskExecutor != null) {
            running = false;

            if (updateTask != null) {
                updateTask.cancel(false);
            }

            updateExecutor.shutdown();
            taskExecutor.shutdown();

            try {
                if (!updateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    updateExecutor.shutdownNow();
                }
                if (!taskExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                updateExecutor.shutdownNow();
                taskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            if (output instanceof PeerKcpOutput peerOutput) {
                peerOutput.close();
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
    public MyKcpMetric getMetric() {
        return myKcp.getMetric();
    }
}
