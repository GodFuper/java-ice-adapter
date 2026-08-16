package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.connection.KeepAliveManager;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeaderCodec;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.receive.ReceiveWindow;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.AckField;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.PendingPacket;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.SendWindow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.nio.ByteBuffer;
import java.util.concurrent.*;

import static com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability.RELIABLE;
import static com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader.PacketType;

/**
 * Core transport engine for Custom Reliable UDP.
 * <p>
 * Manages send/receive operations, retransmission, RTT estimation,
 * and connection keep-alive over a single ice4j Component.
 */
@Slf4j
@Getter
public class ReliableUdpTransport {

    private static final long UPDATE_INTERVAL_MS = 50;
    private static final int MAX_PAYLOAD = 1200; // MTU - header

    private final Peer peer;
    private final Component component;
    private final SendWindow sendWindow;
    private final ReceiveWindow receiveWindow;
    private final RttEstimator rttEstimator;
    private final KeepAliveManager keepAliveManager;
    private final TransportConnectionStats stats;
    @Getter
    private final ScheduledExecutorService updateExecutor;
    private final ConcurrentLinkedQueue<SendTask> sendQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean running = false;
    private volatile int connId;
    private volatile long lastNackTime = 0;
    // Per-sequence NACK throttle to prevent duplicate NACKs for the same sequence
    private final ConcurrentHashMap<Integer, Long> lastNackSeqTime = new ConcurrentHashMap<>();
    private static final long NACK_PER_SEQ_COOLDOWN_MS = 100;

    public ReliableUdpTransport(Peer peer, Component component, int connId) {
        this.peer = peer;
        this.component = component;
        this.connId = connId;
        this.rttEstimator = new RttEstimator();
        this.sendWindow = new SendWindow(64, 65536, rttEstimator);
        this.receiveWindow = new ReceiveWindow();
        this.stats = new TransportConnectionStats();
        this.updateExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CustomRelay-Update-%s".formatted(peer.getPeerIdentifier()));
            t.setDaemon(true);
            return t;
        });
        this.keepAliveManager = new KeepAliveManager(updateExecutor, this::sendKeepAlive, this::onIdleTimeout);
    }

    /**
     * Start the transport update loop.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        keepAliveManager.start();

        updateExecutor.scheduleAtFixedRate(this::update, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.debug("CustomRelay transport started for peer {}", peer.getPeerIdentifier());
    }

    /**
     * Stop the transport.
     */
    public void stop() {
        running = false;
        keepAliveManager.stop();
        updateExecutor.shutdown();
        try {
            if (!updateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                updateExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            updateExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sendWindow.clear();
        log.debug("CustomUdp transport stopped for peer {}", peer.getPeerIdentifier());
    }

    /**
     * Send data over the transport.
     */
    public void send(int channel, Reliability reliability, byte[] payload) {
        SendTask task = new SendTask(channel, reliability, payload);
        sendQueue.offer(task);
    }

    private void processSendQueue() {
        SendTask task;
        while ((task = sendQueue.poll()) != null) {
            byte[] payload = task.payload();
            int payloadLen = payload.length;
            if (payloadLen > MAX_PAYLOAD) {
                log.warn("Payload {} bytes exceeds MTU, truncating", payloadLen);
                payloadLen = MAX_PAYLOAD;
            }

            int seq = sendWindow.getNextSeq();
            PacketHeader header = buildOutgoingHeader(task.channel(), task.reliability(), seq, payloadLen);

            byte[] packet = PacketHeaderCodec.encode(header, payload);
            sendPacket(packet, task.channel(), task.reliability(), seq);
            stats.recordSent(packet.length);
        }
    }

    private void sendPacket(byte[] packet, int channel, Reliability reliability, int seq) {
        try {
            component.send(packet, 0, packet.length);
            log.trace("Sent {} bytes to {}", packet.length, peer.getPeerIdentifier());

            if (reliability == RELIABLE || reliability == Reliability.RELIABLE_ORDERED) {
                // Extract user payload from full packet bytes (skip header)
                byte[] userPayload = extractPayload(packet);
                PendingPacket pending = new PendingPacket(seq, userPayload, channel, reliability);
                sendWindow.add(pending);
            }
        } catch (Exception e) {
            log.error("Failed to send packet to {}", peer.getPeerIdentifier(), e);
        }
    }

    private byte[] extractPayload(byte[] fullPacket) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(fullPacket);
            PacketHeader header = PacketHeaderCodec.decode(buffer);
            byte[] payload = new byte[header.getPayloadLen()];
            buffer.get(payload);
            return payload;
        } catch (Exception e) {
            return fullPacket;
        }
    }

    /**
     * Handle incoming packet from ice4j.
     */
    public void onIncomingPacket(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            PacketHeader header = PacketHeaderCodec.decode(buffer);
            log.debug("ReliableUdpTransport: incoming packet from peer {}, header={}", peer.getPeerIdentifier(), header);
            byte[] payload = new byte[header.getPayloadLen()];
            buffer.get(payload);

            // Handle KEEP_ALIVE: process ACK from keep-alive only, no payload delivery
            if (header.getType() == PacketType.KEEP_ALIVE) {
                if (header.getAck() > 0) {
                    processIncomingAck(header.getAck(), header.getAckBits());
                }
                keepAliveManager.recordActivity();
                return;
            }

            // Handle NACK from the peer (immediate retransmission request)
            if (header.getType() == PacketType.NACK) {
                log.debug("NACK received from peer: nackSeq={}, nackBits={}", header.getAck(), header.getAckBits());
                processIncomingNack(header.getAck(), header.getAckBits());
                keepAliveManager.recordActivity();
                return;
            }

            // Process incoming ACK from the peer (piggybacked in DATA packet)
            if (header.getAck() > 0) {
                processIncomingAck(header.getAck(), header.getAckBits());
            }

            // Process through receive window
            log.warn("[INCOMING] seq={} reliability={} payloadLen={}", header.getSeq(), header.getReliability(), header.getPayloadLen());
            ReceiveWindow.PacketResult result =
                    receiveWindow.process(header.getSeq(), payload, header.getReliability());
            log.warn("[RESULT] seq={} delivered={} missingCount={}", header.getSeq(), result.delivered(), result.missingSeqs().length);

            if (result.delivered() && result.payload() != null) {
                // Check for missing packets and send NACK
                int[] missingSeqs = result.missingSeqs();
                if (missingSeqs.length > 0) {
                    long now = System.currentTimeMillis();
                    int firstMissing = missingSeqs[0];
                    // Per-sequence throttle: each missing seq can trigger at most 1 NACK per cooldown
                    Long lastSeqNack = lastNackSeqTime.get(firstMissing);
                    log.debug("NACK check: seq={}, firstMissing={}, lastSeqNack={}, throttle={}",
                            header.getSeq(), firstMissing, lastSeqNack,
                            lastSeqNack == null || (now - lastSeqNack) >= NACK_PER_SEQ_COOLDOWN_MS);
                    if (lastSeqNack == null || (now - lastSeqNack) >= NACK_PER_SEQ_COOLDOWN_MS) {
                        lastNackSeqTime.put(firstMissing, now);
                        lastNackTime = now;
                        sendNack(firstMissing, missingSeqs);
                        log.debug("NACK sent for seq {} missing {} seqs", firstMissing, missingSeqs.length);
                    } else {
                        log.debug("NACK throttled for seq {}");
                    }
                }

                // Deliver to peer
                peer.handleData(result.payload());
                stats.recordReceived(data.length);
                keepAliveManager.recordActivity();
            }

            keepAliveManager.recordActivity();
        } catch (Exception e) {
            log.error("Failed to process incoming packet", e);
        }
    }

    /**
     * Main update loop: retransmissions, keep-alive, cleanup.
     */
    private void update() {
        Thread.currentThread().setName("%s|%s".formatted(peer.getPeerIdentifier(), "ReliableUdpTransport"));

        // Process send queue
        processSendQueue();

        // Check for retransmissions
        long now = System.currentTimeMillis();
        long currentRto = rttEstimator.getRtoMs();
        Iterable<PendingPacket> expired = sendWindow.getExpired(now);
        for (PendingPacket packet : expired) {
            // DEBUG: log why this packet expired
            if (packet.getAttempts() <= 5) {
                log.debug("RTO retransmit seq={} elapsed={}ms rto={}ms nackPending={}",
                        packet.getSeq(), packet.getElapsedSinceLastSend(),
                        currentRto, packet.hasNackRetransmitPending(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS));
            }
            // Log for NACK-tracked packets
            log.debug("RTO check seq={} attempts={} nackPending={} elapsed={}",
                    packet.getSeq(), packet.getAttempts(),
                    packet.hasNackRetransmitPending(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS),
                    packet.getElapsedSinceLastSend());
            stats.recordRetransmission();
            stats.recordTimeout();

            // Update pending packet metadata before retransmission
            packet.recordSend();

            // Resend
            PacketHeader header = buildOutgoingHeader(
                    packet.getChannel(), packet.getReliability(), packet.getSeq(), packet.getPayload().length);

            byte[] packetData = PacketHeaderCodec.encode(header, packet.getPayload());
            try {
                component.send(packetData, 0, packetData.length);
                stats.recordSent(packetData.length);
            } catch (Exception e) {
                log.error("Retransmission send failed", e);
            }
        }

        // Cleanup old packets
        receiveWindow.cleanup(now);
    }

    /**
     * Process incoming ACK from the peer.
     * Uses cumulative ACK (ackSeq) + selective ACK (bitfield) to remove
     * acknowledged packets from the send window and record RTT.
     */
    private void processIncomingAck(int ackSeq, long ackBits) {
        // Cumulative ACK: remove all packets up to ackSeq
        sendWindow.acknowledgeUpTo(ackSeq);

        // Selective ACK: remove specific packets around ackSeq
        sendWindow.acknowledgeWithBitfield(ackSeq, ackBits);

        // Record RTT for acknowledged packets
        sendWindow.recordRttForAcked(ackSeq, rttEstimator);
    }

    /**
     * Process incoming NACK from the peer (immediate retransmission request).
     * Uses coalescing to prevent duplicate NACK-triggered retransmissions.
     */
    private void processIncomingNack(int nackSeq, long nackBits) {
        log.debug("NACK received for seq {}", nackSeq);

        // NACK coalescing: skip if we already retransmitted this seq recently
        if (sendWindow.shouldSkipNackRetransmit(nackSeq)) {
            log.trace("Skipping NACK retransmit for seq {} (coalesced)", nackSeq);
            return;
        }

        // Find packet and retransmit immediately
        PendingPacket toRetransmit = sendWindow.getPendingPacket(nackSeq);
        if (toRetransmit != null) {
            // Check max retransmission limit
            if (toRetransmit.isMaxRetransmitted()) {
                log.warn("Giving up on seq {} (max retransmissions exceeded: {})", nackSeq, toRetransmit.getAttempts());
                sendWindow.remove(nackSeq);
                return;
            }

            stats.recordNackRetransmission();
            sendWindow.recordNackRetransmitTime(nackSeq);

            // Update the pending packet's nack-specific timestamp so RTO won't fire
            // This MUST be recordNackSend(), NOT recordSend(), because getExpired()
            // checks hasNackRetransmitPending() which relies on lastNackTriggeredSendTime
            toRetransmit.recordNackSend();

            // Actually send the packet immediately
            try {
                log.warn("NACK retransmission {}", toRetransmit);
                PacketHeader header = buildOutgoingHeader(
                        toRetransmit.getChannel(), toRetransmit.getReliability(), toRetransmit.getSeq(), toRetransmit.getPayload().length);
                byte[] packetData = PacketHeaderCodec.encode(header, toRetransmit.getPayload());
                component.send(packetData, 0, packetData.length);
                stats.recordSent(packetData.length);
            } catch (Exception e) {
                log.error("NACK retransmission send failed", e);
            }
        }
    }

    /**
     * Send immediate retransmission without waiting for RTO.
     */
    private void sendImmediateRetransmission(PendingPacket packet) {
        packet.recordSend();

        PacketHeader header = buildOutgoingHeader(
                packet.getChannel(), packet.getReliability(), packet.getSeq(), packet.getPayload().length);

        byte[] packetData = PacketHeaderCodec.encode(header, packet.getPayload());
        try {
            component.send(packetData, 0, packetData.length);
            log.debug("Immediate retransmission for seq {}", packet.getSeq());
            stats.recordSent(packetData.length);
        } catch (Exception e) {
            log.error("Immediate retransmission send failed", e);
        }
    }

    /**
     * Send NACK packet to request retransmission of missing packets.
     */
    private void sendNack(int nackSeq, int[] nackSeqs) {
        if (nackSeq == 0) {
            return;
        }

        long nackBits = AckField.toAckBits(nackSeq, nackSeqs);

        PacketHeader header = PacketHeader.builder()
                .type(PacketType.NACK)
                .connId(connId)
                .seq(0)
                .ack(nackSeq)
                .ackBits(nackBits)
                .channel(0)
                .payloadLen(0)
                .reliability(Reliability.UNRELIABLE)
                .build();

        byte[] packet = PacketHeaderCodec.encode(header, new byte[0]);
        try {
            log.debug("NACK sending to component: seq={}, nackSeq={}", nackSeq, nackSeq);
            component.send(packet, 0, packet.length);
            log.debug("NACK sent via component for seq {}", nackSeq);
        } catch (Exception e) {
            log.error("NACK send failed for seq {}", nackSeq, e);
        }
    }

    /**
     * Build outgoing DATA packet header with piggybacked ACK.
     * Uses cumulative ACK (last delivered seq) + selective ACK (bitfield).
     */
    private PacketHeader buildOutgoingHeader(int channel, Reliability reliability, int seq, int payloadLen) {
        // Build selective ACK bitfield from received packets
        int lastDelivered = receiveWindow.getLastDeliveredSeq();
        int[] recentSeqs = receiveWindow.getRecentReceivedSeqs(AckField.WINDOW_SIZE);
        long ackBits = AckField.toAckBits(lastDelivered, recentSeqs);

        return PacketHeader.builder()
                .type(PacketType.DATA)
                .connId(connId)
                .seq(seq)
                .ack(lastDelivered)
                .ackBits(ackBits)
                .channel(channel)
                .payloadLen(payloadLen)
                .reliability(reliability)
                .build();
    }

    private void sendKeepAlive() {
        // Build ACK info like a DATA packet would
        int lastDelivered = receiveWindow.getLastDeliveredSeq();
        int[] recentSeqs = receiveWindow.getRecentReceivedSeqs(AckField.WINDOW_SIZE);
        long ackBits = AckField.toAckBits(lastDelivered, recentSeqs);

        PacketHeader header = PacketHeader.builder()
                .type(PacketType.KEEP_ALIVE)
                .connId(connId)
                .seq(0)
                .ack(lastDelivered)
                .ackBits(ackBits)
                .channel(0)
                .payloadLen(0)
                .reliability(Reliability.UNRELIABLE)
                .build();

        byte[] keepAlivePacket = PacketHeaderCodec.encode(header, new byte[0]);
        try {
            component.send(keepAlivePacket, 0, keepAlivePacket.length);
        } catch (Exception e) {
            log.error("Keep-alive send failed", e);
        }
    }

    private void onIdleTimeout() {
        log.warn("CustomRelay idle timeout for peer {}", peer.getPeerIdentifier());
        peer.lostConnect();
    }

    private record SendTask(int channel, Reliability reliability, byte[] payload) {
    }
}
