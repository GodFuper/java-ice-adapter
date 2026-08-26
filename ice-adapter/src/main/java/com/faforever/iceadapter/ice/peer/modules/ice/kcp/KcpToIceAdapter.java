package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.backblaze.erasure.FecAdapt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.ChannelConfig;
import kcp.KcpConfig;
import kcp.KcpOutput;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import threadPool.netty.NettyMessageExecutorPool;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * KCP adapter that extends {@link Ice4jUkcp} to inherit all kcp-base logic
 * (WriteTask, ReadTask, FEC, buffer queues, thread scheduling) via inheritance.
 */
@Slf4j
public class KcpToIceAdapter implements KcpTransport {

    private final NettyMessageExecutorPool pool = new NettyMessageExecutorPool(1);

    /**
     * FEC configuration: 12 data shards + 4 parity shards = 33% redundancy
     */
    private static final int FEC_DATA_SHARDS = 12;
    private static final int FEC_PARITY_SHARDS = 4;

    private final String name;
    private final Ice4jUkcp ice4jUkcp;

    @Getter
    private volatile boolean running = false;
    private ScheduledExecutorService updateExecutor;
    private Future<?> updateTask;

    /**
     * Create and start a new UkcpAdapter.
     *
     * @param conv       conversation ID
     * @param name       peer identifier for logging
     * @param output     callback for raw KCP packets to send
     * @param handleData consumer for received application data
     */
    public KcpToIceAdapter(int conv, String name, KcpOutput output, Consumer<byte[]> handleData) {
        this.name = name;
        ChannelConfig channelConfig = buildChannelConfig(conv);
        Ice4jKcpChannelManager manager = new Ice4jKcpChannelManager();
        ice4jUkcp = new Ice4jUkcp(output, handleData, pool.getIMessageExecutor(), channelConfig, manager);
        manager.add(null, ice4jUkcp, null);

    }

    private static ChannelConfig buildChannelConfig(int conv) {
        KcpConfig kcpConfig = new KcpConfig();
        kcpConfig.setConv(conv);
        kcpConfig.setMtu(1200);
        kcpConfig.setSndwnd(128);
        kcpConfig.setRcvwnd(128);
        kcpConfig.setStream(false);
        kcpConfig.nodelay(true, 5, 3, true);
        kcpConfig.setAckNoDelay(true);
        kcpConfig.setAckMaskSize(32);

        // FEC (Forward Error Correction) — recovers from packet loss without retransmission
        FecAdapt fecAdapt = new FecAdapt(FEC_DATA_SHARDS, FEC_PARITY_SHARDS);

        ChannelConfig channelConfig = new ChannelConfig(kcpConfig);
        channelConfig.setTimeoutMillis(30000L);
        channelConfig.setFastFlush(true);
        // Note: CRC32 + FEC not compatible in kcp-base — CRC32 offset conflicts with FEC header
        channelConfig.setCrc32Check(false);
        channelConfig.setFecAdapt(fecAdapt);
        channelConfig.setReadBufferSize(-1);
        channelConfig.setWriteBufferSize(-1);
        return channelConfig;
    }

    /**
     * Send application data through KCP.
     * The data will be queued in writeBuffer and processed by WriteTask.
     *
     * @param payload application data to send
     */
    public void send(byte[] payload) {
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            if (!ice4jUkcp.write(buf)) {
                log.warn("Failed to send to KCP adapter '{}' — buffer full", name);
            }
        } finally {
            buf.release();
        }
    }

    /**
     * Input a raw UDP packet from the peer.
     * The packet will be decoded by ReadTask and delivered via handleData consumer.
     *
     * @param data   raw packet bytes
     * @param offset packet offset
     * @param length packet length
     */
    @Override
    public void onReceive(byte[] data, int offset, int length) {
        ByteBuf packetBuf = Unpooled.wrappedBuffer(data, offset, length);
        try {
            ice4jUkcp.receivedPacket(packetBuf);
        } catch (Exception e) {
            packetBuf.release();
            log.error("KCP input failed for '{}', packetLength={}", name, length, e);
        }
    }

    /**
     * KCP state machine update loop — runs dynamically based on KCP timer state.
     * Uses the next update timestamp returned by updateKcp to schedule the next invocation,
     * avoiding unnecessary wake-ups when KCP is idle.
     * ReadTask and WriteTask are triggered asynchronously via executor when data arrives.
     */
    public void runUpdateLoop() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = ice4jUkcp.updateKcp(now);
        if (running) {
            scheduleNextUpdate(next);
        }
    }

    private void scheduleNextUpdate(long next) {
        long delayNs = Math.max(1, next - System.currentTimeMillis());
        updateTask = updateExecutor.schedule(this::runUpdateLoop, delayNs, TimeUnit.MILLISECONDS);
    }

    /**
     * Start the KCP update loop.
     * This must be called after construction to begin KCP operation.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        updateExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KCP-Update-" + name);
            t.setDaemon(true);
            return t;
        });
        updateTask = updateExecutor.submit(this::runUpdateLoop);
        log.info("UkcpAdapter '{}' started with FEC={}+{}", name, FEC_DATA_SHARDS, FEC_PARITY_SHARDS);
    }

    /**
     * Stop the KCP adapter and release resources.
     */
    public void stop() {
        log.info("UkcpAdapter '{}' stopped", name);
        if (!running) {
            return;
        }
        running = false;

        if (updateTask != null) {
            updateTask.cancel(false);
        }
        if (updateExecutor != null) {
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

        try {
            ice4jUkcp.close();
        } catch (Exception e) {
            log.error("Error closing KCP adapter '{}'", name, e);
        }
        log.info("UkcpAdapter '{}' stopped", name);
    }

    public int getConv() {
        return ice4jUkcp.getConv();
    }

    @Override
    public void close() {
        stop();
    }

    // ---- Getters for statistics ----

    public long getBytesSent() {
        return 0;
    }

    public long getBytesReceived() {
        // TODO: Re-add bytesReceived tracking when input() flow is fully integrated
        return 0;
    }

}
