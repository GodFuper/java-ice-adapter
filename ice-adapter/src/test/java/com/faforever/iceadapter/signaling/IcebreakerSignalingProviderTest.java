package com.faforever.iceadapter.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.icebreaker.IcebreakerHttpClient;
import com.faforever.iceadapter.icebreaker.IcebreakerMessageConverter;
import com.faforever.iceadapter.icebreaker.IcebreakerSseListener;
import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IcebreakerSignalingProviderTest {

    private static final long GAME_ID = 100L;
    private static final int LOCAL_PLAYER_ID = 1;

    private IcebreakerHttpClient httpClient;
    private IcebreakerSseListener sseListener;
    private GameSession gameSession;
    private IceOptions options;
    private Map<Integer, Peer> peers;
    private IcebreakerSignalingProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(IcebreakerHttpClient.class);
        sseListener = mock(IcebreakerSseListener.class);
        gameSession = mock(GameSession.class);
        options = mock(IceOptions.class);
        peers = new ConcurrentHashMap<>();

        when(httpClient.sendEvent(any(Long.class), any(EventMessageDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gameSession.getOptions()).thenReturn(options);
        when(gameSession.getPeers()).thenReturn(peers);
        when(options.isForceRelay()).thenReturn(false);

        provider =
                new IcebreakerSignalingProvider(GAME_ID, LOCAL_PLAYER_ID, httpClient, sseListener, () -> gameSession);
    }

    @Test
    @DisplayName("init() should start the SSE listener")
    void testInit_startsSseListener() {
        provider.init();
        verify(sseListener).start();
    }

    @Test
    @DisplayName("sendSignalingMessage() should convert CandidatesMessage to DTO and send via httpClient")
    void testSendSignalingMessage_convertsAndCallsHttpClient() {
        CandidatePacket candidate = new CandidatePacket(
                "foundation1", "udp", 2130706431L, "192.168.1.1", 50000, CandidateType.HOST_CANDIDATE, 1, "0", null, 0);
        CandidatesMessage msg = new CandidatesMessage(LOCAL_PLAYER_ID, 2, "v=0\r\nsdp", "offer", List.of(candidate));

        provider.sendSignalingMessage(msg);

        ArgumentCaptor<EventMessageDto> captor = ArgumentCaptor.forClass(EventMessageDto.class);
        verify(httpClient).sendEvent(eq(GAME_ID), captor.capture());

        EventMessageDto sent = captor.getValue();
        assertInstanceOf(EventMessageDto.Candidates.class, sent);
        EventMessageDto.Candidates candidatesDto = (EventMessageDto.Candidates) sent;
        assertEquals(GAME_ID, candidatesDto.gameId());
        assertEquals(LOCAL_PLAYER_ID, candidatesDto.senderId());
        assertEquals(2L, candidatesDto.recipientId());
        assertEquals("v=0\r\nsdp", candidatesDto.session().path("sdp").asText());
        assertEquals("offer", candidatesDto.session().path("type").asText());
    }

    @Test
    @DisplayName("onPeerDisconnected() should send PeerClosing event for the specific peer")
    void testOnPeerDisconnected_sendsPeerClosingDto() {
        provider.onPeerDisconnected(42);

        ArgumentCaptor<EventMessageDto> captor = ArgumentCaptor.forClass(EventMessageDto.class);
        verify(httpClient).sendEvent(eq(GAME_ID), captor.capture());

        EventMessageDto sent = captor.getValue();
        assertInstanceOf(EventMessageDto.PeerClosing.class, sent);
        EventMessageDto.PeerClosing closingDto = (EventMessageDto.PeerClosing) sent;
        assertEquals(GAME_ID, closingDto.gameId());
        assertEquals(LOCAL_PLAYER_ID, closingDto.senderId());
        assertEquals(42L, closingDto.recipientId());
    }

    @Test
    @DisplayName("close() should send session-wide PeerClosing and close SSE listener")
    void testClose_sendsSessionClosingAndClosesSseListener() {
        provider.close();

        ArgumentCaptor<EventMessageDto> captor = ArgumentCaptor.forClass(EventMessageDto.class);
        verify(httpClient).sendEvent(eq(GAME_ID), captor.capture());

        EventMessageDto sent = captor.getValue();
        assertInstanceOf(EventMessageDto.PeerClosing.class, sent);
        EventMessageDto.PeerClosing closingDto = (EventMessageDto.PeerClosing) sent;
        assertEquals(GAME_ID, closingDto.gameId());
        assertEquals(LOCAL_PLAYER_ID, closingDto.senderId());
        assertNull(closingDto.recipientId());

        verify(sseListener).close();
    }

    @Test
    @DisplayName("handleIncomingEvent() should deliver candidates to existing peer")
    void testHandleIncomingEvent_candidatesDeliveredToExistingPeer() {
        Peer peer = mock(Peer.class);
        peers.put(2, peer);

        CandidatesMessage original = new CandidatesMessage(2, LOCAL_PLAYER_ID, "v=0\r\nanswer", "answer", List.of());
        EventMessageDto.Candidates dto = IcebreakerMessageConverter.toIcebreakerCandidates(GAME_ID, original);

        provider.handleIncomingEvent(dto);

        ArgumentCaptor<CandidatesMessage> captor = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(peer).iceMessageFromRPC(captor.capture());
        CandidatesMessage received = captor.getValue();
        assertEquals(2, received.srcId());
        assertEquals(LOCAL_PLAYER_ID, received.destId());
        assertEquals("v=0\r\nanswer", received.password());
    }

    @Test
    @DisplayName("handleIncomingEvent() should create peer and deliver candidates if peer is missing")
    void testHandleIncomingEvent_candidatesPeerCreatedIfMissing() {
        Peer peer = mock(Peer.class);
        when(gameSession.connectToPeer(anyString(), eq(2), anyBoolean(), anyInt(), any(AllowCombination.class)))
                .thenAnswer(inv -> {
                    peers.put(2, peer);
                    return 5000;
                });

        CandidatesMessage original = new CandidatesMessage(2, LOCAL_PLAYER_ID, "v=0\r\noffer", "offer", List.of());
        EventMessageDto.Candidates dto = IcebreakerMessageConverter.toIcebreakerCandidates(GAME_ID, original);

        provider.handleIncomingEvent(dto);

        verify(gameSession).connectToPeer("2", 2, true, 0, AllowCombination.ALL);
        verify(peer).iceMessageFromRPC(any(CandidatesMessage.class));
    }

    @Test
    @DisplayName("handleIncomingEvent() should initiate peer connection on Connected event when local is offerer")
    void testHandleIncomingEvent_connectedWhenLocalIsOfferer_initiatesConnection() {
        // LOCAL_PLAYER_ID = 1, remote senderId = 2 -> 1 < 2, so local is Offerer
        EventMessageDto.Connected connected = new EventMessageDto.Connected(GAME_ID, 2L, null);

        provider.handleIncomingEvent(connected);

        verify(gameSession).connectToPeer("2", 2, true, 0, AllowCombination.ALL);
    }

    @Test
    @DisplayName("handleIncomingEvent() should not initiate connection on Connected event when local is answerer")
    void testHandleIncomingEvent_connectedWhenLocalIsAnswerer_doesNotInitiate() {
        // LOCAL_PLAYER_ID = 5, remote senderId = 2 -> 5 > 2, local is Answerer
        IcebreakerSignalingProvider answererProvider =
                new IcebreakerSignalingProvider(GAME_ID, 5, httpClient, sseListener, () -> gameSession);

        EventMessageDto.Connected connected = new EventMessageDto.Connected(GAME_ID, 2L, null);

        answererProvider.handleIncomingEvent(connected);

        verify(gameSession, never()).connectToPeer(anyString(), anyInt(), anyBoolean(), anyInt(), any());
    }

    @Test
    @DisplayName("handleIncomingEvent() should disconnect peer on PeerClosing event")
    void testHandleIncomingEvent_peerClosing_disconnectsPeer() {
        EventMessageDto.PeerClosing closing = new EventMessageDto.PeerClosing(GAME_ID, 2L, null);

        provider.handleIncomingEvent(closing);

        verify(gameSession).disconnectFromPeer(2);
    }

    @Test
    @DisplayName("handleIncomingEvent() should ignore own messages")
    void testHandleIncomingEvent_ignoresOwnMessages() {
        EventMessageDto.Connected ownConnected = new EventMessageDto.Connected(GAME_ID, LOCAL_PLAYER_ID, null);
        EventMessageDto.PeerClosing ownClosing = new EventMessageDto.PeerClosing(GAME_ID, LOCAL_PLAYER_ID, null);

        CandidatesMessage ownMsg = new CandidatesMessage(LOCAL_PLAYER_ID, 2, "v=0\r\nsdp", "offer", List.of());
        EventMessageDto.Candidates ownCandidates = IcebreakerMessageConverter.toIcebreakerCandidates(GAME_ID, ownMsg);

        provider.handleIncomingEvent(ownConnected);
        provider.handleIncomingEvent(ownClosing);
        provider.handleIncomingEvent(ownCandidates);

        verify(gameSession, never()).connectToPeer(anyString(), anyInt(), anyBoolean(), anyInt(), any());
        verify(gameSession, never()).disconnectFromPeer(anyInt());
    }

    @Test
    @DisplayName("handleIncomingEvent() should ignore messages addressed to other recipients")
    void testHandleIncomingEvent_ignoresMessagesForOtherRecipients() {
        // recipientId is 999L while local is 1
        EventMessageDto.Connected otherConnected = new EventMessageDto.Connected(GAME_ID, 2L, 999L);
        EventMessageDto.PeerClosing otherClosing = new EventMessageDto.PeerClosing(GAME_ID, 2L, 999L);

        CandidatesMessage msg = new CandidatesMessage(2, 999, "v=0\r\nsdp", "offer", List.of());
        EventMessageDto.Candidates otherCandidates = IcebreakerMessageConverter.toIcebreakerCandidates(GAME_ID, msg);

        provider.handleIncomingEvent(otherConnected);
        provider.handleIncomingEvent(otherClosing);
        provider.handleIncomingEvent(otherCandidates);

        verify(gameSession, never()).connectToPeer(anyString(), anyInt(), anyBoolean(), anyInt(), any());
        verify(gameSession, never()).disconnectFromPeer(anyInt());
    }

    @Test
    @DisplayName("incoming PeerClosing should not echo back PeerClosing to httpClient")
    void testIncomingPeerClosing_doesNotEchoBackPeerClosing() {
        // Simulate GameSession calling provider.onPeerDisconnected when disconnectFromPeer is executed
        doAnswer(inv -> {
                    provider.onPeerDisconnected(2);
                    return null;
                })
                .when(gameSession)
                .disconnectFromPeer(2);

        EventMessageDto.PeerClosing closing = new EventMessageDto.PeerClosing(GAME_ID, 2L, (long) LOCAL_PLAYER_ID);
        provider.handleIncomingEvent(closing);

        verify(gameSession).disconnectFromPeer(2);
        // Verify httpClient.sendEvent was NOT called as an echo
        verify(httpClient, never()).sendEvent(any(Long.class), any(EventMessageDto.class));
    }
}
