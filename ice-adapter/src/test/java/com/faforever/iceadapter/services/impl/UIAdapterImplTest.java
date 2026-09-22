package com.faforever.iceadapter.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import java.util.Collections;
import java.util.Map;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

class UIAdapterImplTest {

    @Test
    void testGetWebRtcPeerInfoListPopulatesAndUpdates() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        GameSession gameSession = mock(GameSession.class);
        when(iceAdapter.getGameSession()).thenReturn(gameSession);

        Peer peer1 = mock(Peer.class);
        when(peer1.getRemoteId()).thenReturn(101);
        when(peer1.getRemoteLogin()).thenReturn("Alice");

        Peer peer2 = mock(Peer.class);
        when(peer2.getRemoteId()).thenReturn(102);
        when(peer2.getRemoteLogin()).thenReturn("Bob");

        when(gameSession.getPeers()).thenReturn(Map.of(101, peer1, 102, peer2));

        UIAdapterImpl uiAdapter = new UIAdapterImpl(iceAdapter);

        ObservableList<WebRtcPeerView> list = uiAdapter.getWebRtcPeerInfoList();

        assertEquals(2, list.size());
        assertEquals(101, list.get(0).getPeerId().get());
        assertEquals("Alice", list.get(0).getLogin().get());
        assertEquals(102, list.get(1).getPeerId().get());
        assertEquals("Bob", list.get(1).getLogin().get());

        // Peer 2 leaves
        when(gameSession.getPeers()).thenReturn(Map.of(101, peer1));
        list = uiAdapter.getWebRtcPeerInfoList();

        assertEquals(1, list.size());
        assertEquals(101, list.get(0).getPeerId().get());

        // All peers leave
        when(gameSession.getPeers()).thenReturn(Collections.emptyMap());
        list = uiAdapter.getWebRtcPeerInfoList();

        assertTrue(list.isEmpty());
    }

    @Test
    void testGetControlTrafficViewListAggregatedAndPerPeer() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        GameSession gameSession = mock(GameSession.class);
        when(iceAdapter.getGameSession()).thenReturn(gameSession);

        com.faforever.iceadapter.webrtc.WebRtcSession session1 = new com.faforever.iceadapter.webrtc.WebRtcSession(null);
        session1.recordControlDataSent(new byte[] {(byte) 'e', 1, 2, 3}); // 4 bytes ECHO sent
        session1.recordControlDataReceived(new byte[] {(byte) 'c', 1, 2}); // 3 bytes COMMAND recv

        com.faforever.iceadapter.webrtc.WebRtcSession session2 = new com.faforever.iceadapter.webrtc.WebRtcSession(null);
        session2.recordControlDataSent(new byte[] {(byte) 'R', 1, 2, 3, 4, 5}); // 6 bytes AUTO_RELAY sent
        session2.recordControlDataReceived(new byte[] {(byte) 'e', 1}); // 2 bytes ECHO recv

        Peer peer1 = mock(Peer.class);
        when(peer1.getRemoteId()).thenReturn(101);
        when(peer1.getRemoteLogin()).thenReturn("Alice");
        when(peer1.getWebRtcSession()).thenReturn(session1);

        Peer peer2 = mock(Peer.class);
        when(peer2.getRemoteId()).thenReturn(102);
        when(peer2.getRemoteLogin()).thenReturn("Bob");
        when(peer2.getWebRtcSession()).thenReturn(session2);

        when(gameSession.getPeers()).thenReturn(Map.of(101, peer1, 102, peer2));

        UIAdapterImpl uiAdapter = new UIAdapterImpl(iceAdapter);

        // Aggregated (All peers: peerId = null or -1)
        ObservableList<com.faforever.iceadapter.dto.ControlTrafficView> aggregatedList = uiAdapter.getControlTrafficViewList(null);
        assertEquals(4, aggregatedList.size());

        // ECHO: sent=4, recv=2, total=6
        com.faforever.iceadapter.dto.ControlTrafficView echoView = aggregatedList.stream()
                .filter(v -> v.getMessageType() == com.faforever.iceadapter.dto.ControlMessageType.ECHO)
                .findFirst().orElseThrow();
        assertEquals(4, echoView.getBytesSent().get());
        assertEquals(2, echoView.getBytesReceived().get());
        assertEquals(6, echoView.getTotalBytes().get());

        // COMMAND: sent=0, recv=3, total=3
        com.faforever.iceadapter.dto.ControlTrafficView cmdView = aggregatedList.stream()
                .filter(v -> v.getMessageType() == com.faforever.iceadapter.dto.ControlMessageType.COMMAND)
                .findFirst().orElseThrow();
        assertEquals(0, cmdView.getBytesSent().get());
        assertEquals(3, cmdView.getBytesReceived().get());
        assertEquals(3, cmdView.getTotalBytes().get());

        // AUTO_RELAY: sent=6, recv=0, total=6
        com.faforever.iceadapter.dto.ControlTrafficView relayView = aggregatedList.stream()
                .filter(v -> v.getMessageType() == com.faforever.iceadapter.dto.ControlMessageType.AUTO_RELAY)
                .findFirst().orElseThrow();
        assertEquals(6, relayView.getBytesSent().get());
        assertEquals(0, relayView.getBytesReceived().get());
        assertEquals(6, relayView.getTotalBytes().get());

        // Specific peer: 101 (Alice)
        ObservableList<com.faforever.iceadapter.dto.ControlTrafficView> peer1List = uiAdapter.getControlTrafficViewList(101);
        com.faforever.iceadapter.dto.ControlTrafficView p1Echo = peer1List.stream()
                .filter(v -> v.getMessageType() == com.faforever.iceadapter.dto.ControlMessageType.ECHO)
                .findFirst().orElseThrow();
        assertEquals(4, p1Echo.getBytesSent().get());
        assertEquals(0, p1Echo.getBytesReceived().get());
        assertEquals(4, p1Echo.getTotalBytes().get());

        com.faforever.iceadapter.dto.ControlTrafficView p1Relay = peer1List.stream()
                .filter(v -> v.getMessageType() == com.faforever.iceadapter.dto.ControlMessageType.AUTO_RELAY)
                .findFirst().orElseThrow();
        assertEquals(0, p1Relay.getTotalBytes().get());
    }
}
