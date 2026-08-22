package com.faforever.iceadapter.ice.peer.modules.ice;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Queue-based delay buffer that simulates network latency with optional jitter.
 * Packets are queued and delivered after a configurable delay, simulating
 * distant peer communication with realistic network characteristics.
 */
@Slf4j
public class DelayBufferModule {
    private final BlockingQueue<DelayBufferModule.DelayedPacket> queue = new LinkedBlockingQueue<>();
    private volatile Thread deliveryThread;
    private volatile boolean running = false;

    private final String peerIdentifier;
    private final DelayBufferModule.PacketDeliveryHandler deliveryHandler;

    public DelayBufferModule(String peerIdentifier, DelayBufferModule.PacketDeliveryHandler deliveryHandler) {
        this.peerIdentifier = peerIdentifier;
        this.deliveryHandler = deliveryHandler;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        deliveryThread = new Thread(this::deliverPackets, "DelayBuffer-%s".formatted(peerIdentifier));
        deliveryThread.setDaemon(true);
        deliveryThread.start();
    }

    public void stop() {
        running = false;
        if (deliveryThread != null) {
            deliveryThread.interrupt();
            try {
                deliveryThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        queue.clear();
    }

    public void enqueue(byte[] data, int delayMs, float jitterPercent) {
        if (!running) {
            return;
        }
        long jitter = (long) (delayMs * jitterPercent / 100.0f);
        long actualDelay = delayMs + (long) (Math.random() * 2 * jitter - jitter);
        actualDelay = Math.max(0, actualDelay);

        DelayedPacket packet = new DelayedPacket(data, System.currentTimeMillis(), actualDelay);
        queue.offer(packet);
        log.trace("Enqueued packet for {} with delay {}ms (base: {}ms, jitter: {}%)", peerIdentifier, actualDelay,
                delayMs, jitterPercent);
    }

    private void deliverPackets() {
        Thread.currentThread().setName("DelayBuffer-%s".formatted(peerIdentifier));
        while (running) {
            try {
                DelayedPacket packet = queue.take();
                long sleepTime = packet.delayMs - (System.currentTimeMillis() - packet.enqueueTime);
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
                log.trace("Delivering delayed packet for {} (delay: {}ms)", peerIdentifier, packet.delayMs);
                deliveryHandler.deliver(packet.data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Delay buffer closed for {}", peerIdentifier);
    }

    public interface PacketDeliveryHandler {
        void deliver(byte[] data);
    }

    private static class DelayedPacket {
        final byte[] data;
        final long enqueueTime;
        final long delayMs;

        DelayedPacket(byte[] data, long enqueueTime, long delayMs) {
            this.data = data;
            this.enqueueTime = enqueueTime;
            this.delayMs = delayMs;
        }
    }
}
