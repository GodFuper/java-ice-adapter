package com.faforever.iceadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.icebreaker.IcebreakerHttpClient;
import com.faforever.iceadapter.icebreaker.dto.IceServerDto;
import com.faforever.iceadapter.icebreaker.dto.SessionGameResponse;
import com.faforever.iceadapter.signaling.IcebreakerSignalingProvider;
import com.faforever.iceadapter.signaling.RpcSignalingProvider;
import com.faforever.iceadapter.webrtc.WebRtcConnectionFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class IceAdapterArgsTest {

    private IceAdapter adapter;
    private WebRtcConnectionFactory mockWebRtcConnectionFactory;

    @BeforeEach
    void setUp() {
        adapter = new IceAdapter();
        mockWebRtcConnectionFactory = mock(WebRtcConnectionFactory.class);
        adapter.setWebRtcConnectionFactory(mockWebRtcConnectionFactory);
    }

    @AfterEach
    void tearDown() {
        if (adapter.getGameSession() != null) {
            adapter.getGameSession().close();
        }
        IceAdapter.INSTANCE = null;
    }

    @Test
    void testCliArgsParsingOnAdapter() {
        new CommandLine(adapter).parseArgs(
                "--id=42",
                "--game-id=100",
                "--login=Player1",
                "--icebreaker-url=https://icebreaker.faforever.com",
                "--access-token=jwt-test-token",
                "--force-turn-relay");

        IceOptions options = adapter.getIceOptions();
        assertNotNull(options);
        assertEquals(42, options.getId());
        assertEquals(100, options.getGameId());
        assertEquals("Player1", options.getLogin());
        assertEquals("https://icebreaker.faforever.com", options.getIcebreakerUrl());
        assertEquals("jwt-test-token", options.getAccessToken());
        assertTrue(options.isForceTurnRelay());
        assertTrue(options.isForceRelay());
        assertTrue(options.isIcebreakerEnabled());
    }

    @Test
    void testCliArgsParsingDirectlyOnOptions() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs(
                "--id=1",
                "--game-id=2",
                "--login=Player",
                "--icebreaker-url=http://localhost:8080/ice",
                "--access-token=token123");

        assertEquals("http://localhost:8080/ice", options.getIcebreakerUrl());
        assertEquals("token123", options.getAccessToken());
        assertFalse(options.isForceTurnRelay());
        assertFalse(options.isForceRelay());
        assertTrue(options.isIcebreakerEnabled());
    }

    @Test
    void testIsIcebreakerEnabled() {
        IceOptions options = new IceOptions();
        assertFalse(options.isIcebreakerEnabled());

        options.setIcebreakerUrl("https://ice.faforever.com");
        assertFalse(options.isIcebreakerEnabled());

        options.setIcebreakerUrl(null);
        options.setAccessToken("some-token");
        assertFalse(options.isIcebreakerEnabled());

        options.setIcebreakerUrl("   ");
        options.setAccessToken("some-token");
        assertFalse(options.isIcebreakerEnabled());

        options.setIcebreakerUrl("https://ice.faforever.com");
        options.setAccessToken("   ");
        assertFalse(options.isIcebreakerEnabled());

        options.setIcebreakerUrl("https://ice.faforever.com");
        options.setAccessToken("valid-token");
        assertTrue(options.isIcebreakerEnabled());
    }

    @Test
    void testForceTurnRelayAffectsIsForceRelay() {
        IceOptions options = new IceOptions();
        assertFalse(options.isForceRelay());
        assertFalse(options.isForceTurnRelay());

        options.setForceTurnRelay(true);
        assertTrue(options.isForceRelay());
        assertTrue(options.isForceTurnRelay());

        options.setForceTurnRelay(false);
        options.setForceRelay(true);
        assertTrue(options.isForceRelay());
        assertFalse(options.isForceTurnRelay());
    }

    @Test
    void testCreateGameSessionWithIcebreakerIntegration() {
        IceOptions options = new IceOptions();
        options.setId(10);
        options.setGameId(999);
        options.setLogin("IcePlayer");
        options.setIcebreakerUrl("https://ice.faforever.com");
        options.setAccessToken("secret-jwt-token");
        adapter.setIceOptions(options);

        IcebreakerHttpClient mockClient = mock(IcebreakerHttpClient.class);
        when(mockClient.fetchSessionToken(999)).thenReturn(CompletableFuture.completedFuture("session-jwt"));
        when(mockClient.getSessionToken()).thenReturn("session-jwt");
        when(mockClient.getHmac()).thenReturn(Optional.of("test-hmac"));

        IceServerDto serverDto = new IceServerDto(
                "turn-1", "turnUser", "turnPass", List.of("turn:turn.faforever.com:3478?transport=tcp"));
        SessionGameResponse gameSessionResponse = new SessionGameResponse("session-1", true, List.of(serverDto));
        when(mockClient.fetchGameSession(999)).thenReturn(CompletableFuture.completedFuture(gameSessionResponse));
        when(mockClient.registerAddresses(999)).thenReturn(CompletableFuture.completedFuture(null));

        adapter.setIcebreakerClient(mockClient);

        GameSession session = adapter.createGameSession();

        assertNotNull(session);
        assertSame(session, adapter.getGameSession());
        assertTrue(options.isForceRelay());

        verify(mockClient).fetchSessionToken(999);
        verify(mockClient).fetchGameSession(999);
        verify(mockClient).registerAddresses(999);

        assertNotNull(adapter.getIcebreakerSignalingProvider());
        assertSame(adapter.getIcebreakerSignalingProvider(), session.getSignalingProvider());
        assertInstanceOf(IcebreakerSignalingProvider.class, session.getSignalingProvider());

        boolean hasTurnServer = session.getIceServers().stream().anyMatch(s -> s.isTurn()
                && "turnUser".equals(s.getTurnUsername())
                && "turnPass".equals(s.getTurnCredential()));
        assertTrue(hasTurnServer, "ICE servers should contain the configured server from icebreaker");
    }

    @Test
    void testCreateGameSessionWithoutIcebreakerUsesRpcSignaling() {
        IceOptions options = new IceOptions();
        options.setId(10);
        options.setGameId(999);
        options.setLogin("LegacyPlayer");
        adapter.setIceOptions(options);

        GameSession session = adapter.createGameSession();

        assertNotNull(session);
        assertInstanceOf(RpcSignalingProvider.class, session.getSignalingProvider());
        assertNull(adapter.getIcebreakerClient());
        assertNull(adapter.getIcebreakerSignalingProvider());
    }

    @Test
    void testShutdownClosesIcebreakerComponents() {
        IceOptions options = new IceOptions();
        options.setId(10);
        options.setGameId(999);
        options.setLogin("IcePlayer");
        options.setIcebreakerUrl("https://ice.faforever.com");
        options.setAccessToken("secret-jwt-token");
        adapter.setIceOptions(options);

        IcebreakerHttpClient mockClient = mock(IcebreakerHttpClient.class);
        when(mockClient.fetchSessionToken(anyLong())).thenReturn(CompletableFuture.completedFuture("session-jwt"));
        when(mockClient.fetchGameSession(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new SessionGameResponse("s1", false, List.of())));
        when(mockClient.registerAddresses(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        adapter.setIcebreakerClient(mockClient);

        adapter.createGameSession();
        IcebreakerSignalingProvider signalingProvider = adapter.getIcebreakerSignalingProvider();
        assertNotNull(signalingProvider);

        adapter.onFAShutdown();

        assertNull(adapter.getGameSession());
        assertNull(adapter.getIcebreakerSignalingProvider());
        assertNull(adapter.getIcebreakerClient());
        verify(mockClient).close();
    }
}
