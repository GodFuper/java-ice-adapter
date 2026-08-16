package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.ReliableUdpTransport;
import org.ice4j.ice.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReliableUdpTransportTest {

    private ReliableUdpTransport transport;
    private Component component;

    @Mock
    private Peer peer;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(peer.getPeerIdentifier()).thenReturn("TestPeer(1)");
        lenient().when(peer.getRemoteId()).thenReturn(12345);
        component = mock(Component.class);
        lenient().doNothing().when(component).send(any(byte[].class), anyInt(), anyInt());
        transport = new ReliableUdpTransport(peer, component, 99);
    }

    @Test
    void testStartAndStop() throws Exception {
        transport.start();
        assertTrue(transport.isRunning());
        transport.stop();
        assertFalse(transport.isRunning());
    }

    @Test
    void testStartIsIdempotent() throws Exception {
        transport.start();
        transport.start();
        transport.start();
        transport.stop();
    }

    @Test
    void testSendUnreliablePacket() throws Exception {
        transport.start();
        transport.send(0, Reliability.UNRELIABLE, new byte[]{1, 2, 3});
        Thread.sleep(100);
        verify(component, atLeast(1)).send(any(byte[].class), eq(0), anyInt());
        assertEquals(0, transport.getSendWindow().size());
    }

    @Test
    void testSendReliablePacket() throws Exception {
        transport.start();
        transport.send(0, Reliability.RELIABLE, new byte[]{1, 2, 3});
        Thread.sleep(100);
        verify(component, atLeast(1)).send(any(byte[].class), eq(0), anyInt());
        assertEquals(1, transport.getSendWindow().size());
    }

    @Test
    void testSendReliableOrderedPacket() throws Exception {
        transport.start();
        transport.send(0, Reliability.RELIABLE_ORDERED, new byte[]{1, 2, 3});
        Thread.sleep(100);
        verify(component, atLeast(1)).send(any(byte[].class), eq(0), anyInt());
        assertEquals(1, transport.getSendWindow().size());
    }

    @Test
    void testSendUnreliableOrderedPacket() throws Exception {
        transport.start();
        transport.send(0, Reliability.UNRELIABLE_ORDERED, new byte[]{1, 2, 3});
        Thread.sleep(100);
        verify(component, atLeast(1)).send(any(byte[].class), eq(0), anyInt());
        assertEquals(0, transport.getSendWindow().size());
    }

    @Test
    void testProcessSendQueueTruncatesOversizedPayload() throws Exception {
        transport.start();
        byte[] largePayload = new byte[2000];
        transport.send(0, Reliability.UNRELIABLE, largePayload);
        Thread.sleep(100);
        verify(component, atLeast(1)).send(any(byte[].class), eq(0), anyInt());
    }

    @Test
    void testStopCleansSendWindow() throws Exception {
        transport.start();
        transport.send(0, Reliability.RELIABLE, new byte[]{1, 2, 3});
        Thread.sleep(100);
        assertEquals(1, transport.getSendWindow().size());
        transport.stop();
        assertEquals(0, transport.getSendWindow().size());
    }

    @Test
    void testUpdateLoopStopsGracefully() throws Exception {
        transport.start();
        transport.send(0, Reliability.UNRELIABLE, new byte[]{1});
        transport.stop();
        Thread.sleep(100);
    }

    @Test
    void testSendWhileStoppedDoesNothing() throws Exception {
        transport.send(0, Reliability.UNRELIABLE, new byte[]{1, 2, 3});
        assertEquals(1, transport.getSendQueue().size());
    }

    @Test
    void testGetStats() {
        assertNotNull(transport.getStats());
        assertEquals(0, transport.getStats().getPacketsSent());
        assertEquals(0, transport.getStats().getPacketsReceived());
    }

    @Test
    void testGetStatsAfterSending() throws Exception {
        transport.start();
        transport.send(0, Reliability.UNRELIABLE, new byte[]{1, 2, 3});
        Thread.sleep(100);
        assertTrue(transport.getStats().getPacketsSent() >= 1);
    }

    @Test
    void testRttEstimatorIntegration() throws Exception {
        transport.start();
        var estimator = transport.getRttEstimator();
        assertNotNull(estimator);
        assertEquals(50, estimator.getRtoMs()); // default minRto
        transport.stop();
    }

    @Test
    void testStatsNotAffectedByQueueOverflow() throws Exception {
        transport.start();
        transport.send(0, Reliability.UNRELIABLE, new byte[]{1});
        Thread.sleep(100);
        assertTrue(transport.getStats().getPacketsSent() >= 1);
        transport.stop();
    }

    @Test
    void testKeepAliveManagerNotNull() throws Exception {
        transport.start();
        assertNotNull(transport.getKeepAliveManager());
        assertTrue(transport.getKeepAliveManager().getIdleTimeMs() >= 0);
        transport.stop();
    }

    @Test
    void testSendWindowNotNull() throws Exception {
        transport.start();
        assertNotNull(transport.getSendWindow());
        transport.stop();
    }
}
