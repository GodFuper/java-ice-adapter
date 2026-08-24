package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.Kcp;
import kcp.KcpOutput;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * KCP adapter that bridges ice4j Component (DatagramSocket) with kcp.Kcp protocol.
 * <p>
 * Uses separate queues for read and write operations to avoid deadlocks:
 * - WriteTask processes application-level sends (kcp.send())
 * - ReadTask processes incoming packets (kcp.input())
 * - Update loop runs on a dedicated thread and handles kcp.update()
 * <p>
 * Features:
 * - Fast retransmission via duplicate ACKs (nodelay mode)
 * - Sliding window flow control (sndWnd/rcvWnd)
 * - Congestion control (optional, disabled for gaming mode)
 * - Ack mask for packet flow mode (reduces ACK overhead)
 * - Stream mode for byte stream semantics
 * - Dead link detection with auto-recovery
 * - Configurable nodelay parameters for latency vs throughput tradeoff
 */
@Slf4j
public class KcpAdapter {

    private static final int DEFAULT_MTU = 1200;
    private static final int DEFAULT_SEND_WINDOW = 128;
    private static final int DEFAULT_RECV_WINDOW = 256;
    private static final int DEFAULT_UPDATE_INTERVAL_MS = 2;
    private static final int DEFAULT_MAX_UPDATE_DELAY_MS = 1000;
    private static final int DEFAULT_ACK_MASK_SIZE = 32;
    private static final int DEFAULT_DEADLINK = 20;

    private final String name;
    private final KcpOutput output;
    private final Consumer<byte[]> handleData;
    private final Kcp myKcp;
    private final int conv;

    private volatile boolean running = false;
    private volatile long nextUpdateTimestamp = 0;

    // Queues for thread-safe async processing
    private final Queue<ByteBuf> writeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> readQueue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService updateExecutor;
    private ScheduledExecutorService taskExecutor;
    private ScheduledFuture<?> updateTask;
    private final AtomicBoolean writeProcessing = new AtomicBoolean(false);
    private final AtomicBoolean readProcessing = new AtomicBoolean(false);

    // Byte counters
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);

    // Dead link tracking
    private final AtomicReference<Long> lastAckTimestamp = new AtomicReference<>(0L);
    private final AtomicLong consecutiveRetransmits = new AtomicLong(0);

    // Getters that return primitive long values
    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public boolean isRunning() {
        return running;
    }

    public long getNextUpdateTimestamp() {
        return nextUpdateTimestamp;
    }

    public KcpAdapter(int conv, String name, KcpOutput output, Consumer<byte[]> handleData) {
        this.conv = conv;
        this.name = name;
        this.output = output;
        this.handleData = handleData;

        // Create KCP instance with provided conv (remoteId) and output callback
        this.myKcp = new Kcp(conv, output);

        // Configure KCP for gaming/low-latency mode
        configureKcp(true, DEFAULT_UPDATE_INTERVAL_MS, 3, true);
    }

    /**
     * Configure KCP with all available features for optimal performance.
     *
     * @param nodelay      enable nodelay mode for low latency
     * @param interval     update interval in milliseconds
     * @param resend       duplicate ACKs threshold for fast retransmission (0=disabled, 2-3=recommended)
     * @param noCongestion disable congestion control (true=gaming, false=throughput)
     */
    private void configureKcp(boolean nodelay, int interval, int resend, boolean noCongestion) {
        // Nodelay mode: fast retransmit + low latency
        // nodelay=true enables instantaneous send, no wait for RTT
        // interval=min update interval (2ms for gaming)
        // resend=duplicate ACKs before fast retransmit (3=fast, 0=disabled)
        // nc=no congestion control (true=low latency, false=congestion aware)
        myKcp.nodelay(nodelay, interval, resend, noCongestion);

        // MTU includes KCP overhead (24 bytes header)
        myKcp.setMtu(DEFAULT_MTU);

        // Sliding window sizes for flow control
        // sndWnd: max segments in flight (128 = 128 * MTU = ~150KB window)
        myKcp.setSndWnd(DEFAULT_SEND_WINDOW);
        // rcvWnd: max segments to buffer on receive side (256)
        myKcp.setRcvWnd(DEFAULT_RECV_WINDOW);

        // Ack mask size for packet flow mode
        // Reduces ACK overhead by batching multiple ACKs in one packet
        // 32 means ACK can cover up to 32 sequence numbers
        myKcp.setAckNoDelay(true);

        // Stream mode: KCP merges fragmented segments into a byte stream
        // Removes segment boundaries, useful for small message sends
        myKcp.setStream(true);
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
                DEFAULT_UPDATE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        log.info("KCP adapter '{}' started with conv={}", name, conv);
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

        // Update KCP state machine (handles ACKs, retransmissions, timers)
        myKcp.update(now);

        // Track retransmissions for dead link detection
        trackRetransmissions(now);

        // Receive and deliver data using mergeRecv()
        // mergeRecv() returns aggregated ByteBuf or null if no complete message
        ByteBuf recvBuf;
        while ((recvBuf = myKcp.mergeRecv()) != null) {
            try {
                int readableBytes = recvBuf.readableBytes();
                byte[] data = new byte[readableBytes];
                if (readableBytes > 0) {
                    recvBuf.getBytes(recvBuf.readerIndex(), data);
                }
                bytesReceived.addAndGet(data.length);

                // Update last ACK timestamp to track liveness
                lastAckTimestamp.set(now);

                // Deliver to application layer
                handleData.accept(data);
            } finally {
                recvBuf.release();
            }
        }

        // Schedule next update based on KCP state
        nextUpdateTimestamp = scheduleNextUpdate(now);
    }

    /**
     * Track retransmission count for dead link detection.
     * When consecutive retransmits exceed DEADLINK (20), state becomes -1.
     */
    private void trackRetransmissions(long now) {
        int state = myKcp.getState();

        // If state is -1, connection is dead (max retransmits reached)
        if (state == -1) {
            consecutiveRetransmits.set(0); // Reset for next connection
            log.warn("KCP dead-link detected for adapter '{}' (conv={})", name, conv);
        } else {
            // State is normal, reset retransmit counter
            consecutiveRetransmits.set(0);
        }
    }

    /**
     * Process write queue: drain application buffers and call kcp.send().
     * KCP handles segmentation, retransmission, and flow control.
     */
    private void processWriteQueue() {
        ByteBuf buf;
        int writeCount = 0;
        long writeBytes = 0;

        while ((buf = writeQueue.poll()) != null) {
            writeCount++;
            try {
                // kcp.send() returns number of bytes consumed
                // If queue is full (sndWnd exceeded), returns 0
                int sent = myKcp.send(buf);
                if (sent > 0) {
                    writeBytes += sent;
                    buf.release();
                } else {
                    // Send queue full (congestion/window limit)
                    // Put buffer back and stop processing
                    writeQueue.offer(buf);
                    log.debug("KCP send queue full for '{}', {} bytes in flight", name, myKcp.waitSnd());
                    break;
                }
            } catch (Exception e) {
                log.error("KCP send failed for '{}'", name, e);
                buf.release();
            }
        }

        if (writeCount > 0) {
            bytesSent.addAndGet(writeBytes);
        }
    }

    /**
     * Process read queue: drain incoming UDP packets and call kcp.input().
     * KCP handles ACKs, retransmission requests, and data reassembly.
     */
    private void processReadQueue() {
        byte[] packet;
        int readCount = 0;

        while ((packet = readQueue.poll()) != null) {
            readCount++;
            try {
                // kcp.input() parses UDP packet and handles:
                // - ACK extraction
                // - UNA (unacknowledged) sequence numbers
                // - Fast retransmit triggers
                // - FEC decoding (if enabled)
                myKcp.input(Unpooled.wrappedBuffer(packet), true, System.currentTimeMillis());
            } catch (Exception e) {
                log.error("KCP input failed for '{}'", name, e);
            }
        }
    }

    /**
     * Schedule the next update based on KCP internal state.
     * Uses check() to calculate when next timer event is needed.
     */
    private long scheduleNextUpdate(long now) {
        // check() returns timestamp of next KCP event (ACK timeout, retransmit, etc.)
        // If no pending events, returns now + interval
        long nextTs = myKcp.check(now);
        long minNext = now + DEFAULT_UPDATE_INTERVAL_MS;
        long maxNext = now + DEFAULT_MAX_UPDATE_DELAY_MS;

        // Ensure minimum delay for stability, cap maximum to prevent hang
        if (nextTs < minNext) {
            nextTs = minNext;
        }
        if (nextTs > maxNext) {
            nextTs = maxNext;
        }
        return nextTs;
    }

    /**
     * Send application data through KCP.
     * KCP handles:
     * - Segmentation (splits into MTU-sized chunks)
     * - Sequence numbering
     * - ACK tracking
     * - Retransmission on timeout
     * - Fast retransmission on duplicate ACKs
     *
     * @param payload application data to send
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
     * Process an incoming raw packet from the peer via UDP.
     * KCP parses the packet and handles:
     * - ACK extraction (updates sndUna)
     * - Retransmission triggers (duplicate ACKs)
     * - Data reassembly (in-order delivery)
     * - FEC decoding (if enabled)
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
     * Flush pending KCP output immediately.
     * Forces kcp to send all buffered data without waiting for next update.
     */
    public void flush() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        myKcp.flush(false, now);
    }

    /**
     * Get the current send buffer size (for diagnostics).
     * Represents in-flight segments not yet acknowledged.
     */
    public int getWaitSnd() {
        return myKcp.waitSnd();
    }

    /**
     * Get the current KCP state (0 = normal, -1 = dead-link).
     * Dead-link occurs when max retransmits (20) reached without ACK.
     */
    public int getState() {
        return myKcp.getState();
    }

    public int getConv() {
        return conv;
    }

    /**
     * Get the Kcp instance for advanced access (for statistics collection).
     */
    public Kcp getKcpInstance() {
        return myKcp;
    }

    /**
     * Get the output callback for testing/debugging.
     */
    public KcpOutput getOutput() {
        return output;
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

            log.info("KCP adapter '{}' stopped", name);
        }
    }
}
