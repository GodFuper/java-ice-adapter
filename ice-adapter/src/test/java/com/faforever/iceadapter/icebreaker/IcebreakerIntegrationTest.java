package com.faforever.iceadapter.icebreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.faforever.iceadapter.icebreaker.dto.SessionGameResponse;
import com.faforever.iceadapter.signaling.IcebreakerSignalingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IcebreakerIntegrationTest {

    private static final long GAME_ID = 100L;
    private static final int PEER_1_ID = 1;
    private static final int PEER_2_ID = 2;

    private ObjectMapper objectMapper;
    private MockIcebreakerServer mockServer;

    private IcebreakerHttpClient client1;
    private IcebreakerHttpClient client2;

    private IcebreakerSignalingProvider provider1;
    private IcebreakerSignalingProvider provider2;

    private GameSession gameSession1;
    private GameSession gameSession2;

    private Map<Integer, Peer> peersMap1;
    private Map<Integer, Peer> peersMap2;

    private Peer peer1InSession;
    private Peer peer2InSession;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        mockServer = new MockIcebreakerServer(objectMapper);
        mockServer.start();

        String jwt1 = createJwt("{\"sub\":\"1\",\"ext\":{\"hmac\":\"secret-hmac-peer-1\"}}");
        String jwt2 = createJwt("{\"sub\":\"2\",\"ext\":{\"hmac\":\"secret-hmac-peer-2\"}}");

        client1 = new IcebreakerHttpClient(mockServer.getUrl(), jwt1, objectMapper);
        client2 = new IcebreakerHttpClient(mockServer.getUrl(), jwt2, objectMapper);

        gameSession1 = mock(GameSession.class);
        gameSession2 = mock(GameSession.class);

        peersMap1 = new ConcurrentHashMap<>();
        peersMap2 = new ConcurrentHashMap<>();

        peer1InSession = mock(Peer.class);
        peer2InSession = mock(Peer.class);

        peersMap1.put(PEER_2_ID, peer1InSession);
        when(gameSession1.getPeers()).thenReturn(peersMap1);

        when(gameSession2.getPeers()).thenReturn(peersMap2);
        when(gameSession2.connectToPeer(
                        anyString(), eq(PEER_1_ID), anyBoolean(), anyInt(), any(AllowCombination.class)))
                .thenAnswer(inv -> {
                    peersMap2.put(PEER_1_ID, peer2InSession);
                    return 5000;
                });
    }

    @AfterEach
    void tearDown() {
        if (provider1 != null) {
            try {
                provider1.close();
            } catch (Exception ignored) {
            }
        }
        if (provider2 != null) {
            try {
                provider2.close();
            } catch (Exception ignored) {
            }
        }
        if (client1 != null) {
            try {
                client1.close();
            } catch (Exception ignored) {
            }
        }
        if (client2 != null) {
            try {
                client2.close();
            } catch (Exception ignored) {
            }
        }
        if (mockServer != null) {
            try {
                mockServer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private String createJwt(String payloadJson) {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummySig";
    }

    private void initProviders() throws InterruptedException {
        IcebreakerSseListener sse1 = IcebreakerSseListener.builder()
                .baseUrl(mockServer.getUrl())
                .gameId(GAME_ID)
                .sessionTokenSupplier(client1::getSessionToken)
                .hmac(client1.getHmac().orElse(null))
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofMillis(200))
                .objectMapper(objectMapper)
                .build();

        provider1 = new IcebreakerSignalingProvider(GAME_ID, PEER_1_ID, client1, sse1, () -> gameSession1);

        IcebreakerSseListener sse2 = IcebreakerSseListener.builder()
                .baseUrl(mockServer.getUrl())
                .gameId(GAME_ID)
                .sessionTokenSupplier(client2::getSessionToken)
                .hmac(client2.getHmac().orElse(null))
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofMillis(200))
                .objectMapper(objectMapper)
                .build();

        provider2 = new IcebreakerSignalingProvider(GAME_ID, PEER_2_ID, client2, sse2, () -> gameSession2);

        provider1.init();
        provider2.init();

        assertTrue(mockServer.awaitPeerConnected(PEER_1_ID, 5, TimeUnit.SECONDS), "Peer 1 SSE must connect");
        assertTrue(mockServer.awaitPeerConnected(PEER_2_ID, 5, TimeUnit.SECONDS), "Peer 2 SSE must connect");
    }

    @Test
    @DisplayName("End-to-end: Handshake, address registration, Offer/Answer exchange and peer disconnection")
    void testEndToEndSignalingExchange_offerAnswerAndPeerDisconnected() throws Exception {
        // Step 1: Handshake and address registration for Peer 1
        String sessionToken1 = client1.fetchSessionToken(GAME_ID).join();
        assertNotNull(sessionToken1);
        assertEquals("mock-session-jwt-1", sessionToken1);

        SessionGameResponse gameSession1Response =
                client1.fetchGameSession(GAME_ID).join();
        assertNotNull(gameSession1Response);
        assertEquals("100", gameSession1Response.id());
        assertFalse(gameSession1Response.forceRelay());
        assertEquals(1, gameSession1Response.servers().size());
        assertEquals("turn-1", gameSession1Response.servers().get(0).id());
        assertEquals(
                List.of("stun:127.0.0.1:3478"),
                gameSession1Response.servers().get(0).urls());

        client1.registerAddresses(GAME_ID).join();

        // Step 2: Handshake and address registration for Peer 2
        String sessionToken2 = client2.fetchSessionToken(GAME_ID).join();
        assertNotNull(sessionToken2);
        assertEquals("mock-session-jwt-2", sessionToken2);

        SessionGameResponse gameSession2Response =
                client2.fetchGameSession(GAME_ID).join();
        assertNotNull(gameSession2Response);
        assertEquals("100", gameSession2Response.id());

        client2.registerAddresses(GAME_ID).join();

        assertEquals(2, mockServer.tokenRequests.get());
        assertEquals(2, mockServer.gameSessionRequests.get());
        assertEquals(2, mockServer.addressRegistrations.get());

        // Step 3: Start SSE listeners and connect both peers
        initProviders();

        // Step 4: Peer 1 initiates and sends Offer to Peer 2
        CandidatePacket candidateOffer = new CandidatePacket(
                "cand1", "udp", 2130706431L, "192.168.1.10", 50001, CandidateType.HOST_CANDIDATE, 1, "0", null, 0);
        CandidatesMessage offerMsg =
                new CandidatesMessage(PEER_1_ID, PEER_2_ID, "v=0\r\nsdp-offer-test", "offer", List.of(candidateOffer));

        provider1.sendSignalingMessage(offerMsg);

        // Step 5: Verify Peer 2 receives Offer via SSE and delivers to Peer
        ArgumentCaptor<CandidatesMessage> captorOffer = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(peer2InSession, timeout(5000)).iceMessageFromRPC(captorOffer.capture());

        CandidatesMessage receivedOffer = captorOffer.getValue();
        assertEquals(PEER_1_ID, receivedOffer.srcId());
        assertEquals(PEER_2_ID, receivedOffer.destId());
        assertTrue(receivedOffer.isOffer());
        assertEquals("v=0\r\nsdp-offer-test", receivedOffer.password());
        assertEquals(1, receivedOffer.candidates().size());
        assertEquals("192.168.1.10", receivedOffer.candidates().get(0).ip());
        assertEquals(50001, receivedOffer.candidates().get(0).port());

        // Step 6: Peer 2 receives Offer and sends Answer to Peer 1
        CandidatePacket candidateAnswer = new CandidatePacket(
                "cand2", "udp", 2130706432L, "192.168.1.20", 50002, CandidateType.HOST_CANDIDATE, 1, "0", null, 0);
        CandidatesMessage answerMsg = new CandidatesMessage(
                PEER_2_ID, PEER_1_ID, "v=0\r\nsdp-answer-test", "answer", List.of(candidateAnswer));

        provider2.sendSignalingMessage(answerMsg);

        // Step 7: Verify Peer 1 receives Answer via SSE
        ArgumentCaptor<CandidatesMessage> captorAnswer = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(peer1InSession, timeout(5000)).iceMessageFromRPC(captorAnswer.capture());

        CandidatesMessage receivedAnswer = captorAnswer.getValue();
        assertEquals(PEER_2_ID, receivedAnswer.srcId());
        assertEquals(PEER_1_ID, receivedAnswer.destId());
        assertTrue(receivedAnswer.isAnswer());
        assertEquals("v=0\r\nsdp-answer-test", receivedAnswer.password());
        assertEquals(1, receivedAnswer.candidates().size());
        assertEquals("192.168.1.20", receivedAnswer.candidates().get(0).ip());
        assertEquals(50002, receivedAnswer.candidates().get(0).port());

        // Step 8: Peer 1 disconnects Peer 2 -> Peer 2 receives PeerClosing
        provider1.onPeerDisconnected(PEER_2_ID);

        verify(gameSession2, timeout(5000)).disconnectFromPeer(PEER_1_ID);
    }

    @Test
    @DisplayName("End-to-end: provider.close() broadcasts PeerClosing to other peers")
    void testEndToEndSignalingExchange_sessionCloseBroadcastsPeerClosing() throws Exception {
        client1.fetchSessionToken(GAME_ID).join();
        client2.fetchSessionToken(GAME_ID).join();

        initProviders();

        // Peer 1 closes session -> should broadcast PeerClosing
        provider1.close();

        // Peer 2 must receive PeerClosing and disconnect Peer 1
        verify(gameSession2, timeout(5000)).disconnectFromPeer(PEER_1_ID);
        assertFalse(provider1.getSseListener().isRunning(), "Provider 1 SSE listener should be stopped");
    }

    static class MockIcebreakerServer implements AutoCloseable {
        private final HttpServer httpServer;
        private final ExecutorService executor;
        private final ObjectMapper objectMapper;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Map<Long, OutputStream> sseStreams = new ConcurrentHashMap<>();
        private final Map<Long, CountDownLatch> peerConnectedLatches = new ConcurrentHashMap<>();

        final AtomicInteger tokenRequests = new AtomicInteger();
        final AtomicInteger addressRegistrations = new AtomicInteger();
        final AtomicInteger gameSessionRequests = new AtomicInteger();
        final AtomicInteger eventsPosted = new AtomicInteger();

        MockIcebreakerServer(ObjectMapper objectMapper) throws IOException {
            this.objectMapper = objectMapper;
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mock-icebreaker-server-worker");
                t.setDaemon(true);
                return t;
            });
            this.httpServer.setExecutor(executor);
            setupRoutes();
        }

        void start() {
            httpServer.start();
        }

        int getPort() {
            return httpServer.getAddress().getPort();
        }

        String getUrl() {
            return "http://127.0.0.1:" + getPort();
        }

        boolean awaitPeerConnected(long peerId, long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch latch = peerConnectedLatches.computeIfAbsent(peerId, id -> new CountDownLatch(1));
            if (sseStreams.containsKey(peerId)) {
                return true;
            }
            return latch.await(timeout, unit);
        }

        private void setupRoutes() {
            httpServer.createContext("/session/token", this::handleToken);
            httpServer.createContext("/session/game", this::handleGameRouting);
        }

        private void handleToken(HttpExchange exchange) throws IOException {
            tokenRequests.incrementAndGet();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String hmac = exchange.getRequestHeaders().getFirst("X-HMAC");
            if (auth == null || !auth.startsWith("Bearer ") || hmac == null || hmac.isBlank()) {
                respond(exchange, 400, "{\"error\":\"Missing Authorization or X-HMAC\"}");
                return;
            }

            String sessionJwt;
            if (hmac.contains("peer-1") || auth.contains("peer-1")) {
                sessionJwt = "mock-session-jwt-1";
            } else if (hmac.contains("peer-2") || auth.contains("peer-2")) {
                sessionJwt = "mock-session-jwt-2";
            } else {
                sessionJwt = "mock-session-jwt";
            }

            respond(exchange, 200, "{\"jwt\":\"" + sessionJwt + "\"}");
        }

        private void handleGameRouting(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.matches(".*/addresses$") && "POST".equalsIgnoreCase(method)) {
                handleAddresses(exchange);
            } else if (path.matches(".*/events$") && "GET".equalsIgnoreCase(method)) {
                handleGetEvents(exchange);
            } else if (path.matches(".*/events$") && "POST".equalsIgnoreCase(method)) {
                handlePostEvents(exchange);
            } else if (path.matches("/session/game/\\d+$") && "GET".equalsIgnoreCase(method)) {
                handleGetGame(exchange);
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }

        private void handleGetGame(HttpExchange exchange) throws IOException {
            gameSessionRequests.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String json =
                    """
                    {
                        "id": "100",
                        "forceRelay": false,
                        "servers": [
                            {
                                "id": "turn-1",
                                "username": "u",
                                "credential": "c",
                                "urls": ["stun:127.0.0.1:3478"]
                            }
                        ]
                    }
                    """;
            respond(exchange, 200, json);
        }

        private void handleAddresses(HttpExchange exchange) throws IOException {
            addressRegistrations.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private void handleGetEvents(HttpExchange exchange) throws IOException {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"text/event-stream".equals(accept) || auth == null || !auth.startsWith("Bearer ")) {
                respond(exchange, 400, "{\"error\":\"Bad request or unauthorized\"}");
                return;
            }

            String hmac = exchange.getRequestHeaders().getFirst("X-HMAC");
            long peerId;
            if ((auth != null && auth.contains("jwt-1")) || (hmac != null && hmac.contains("peer-1"))) {
                peerId = 1L;
            } else if ((auth != null && auth.contains("jwt-2")) || (hmac != null && hmac.contains("peer-2"))) {
                peerId = 2L;
            } else {
                peerId = sseStreams.containsKey(1L) ? 2L : 1L;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();
            // Send initial comment to flush headers and establish stream
            os.write(":connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            sseStreams.put(peerId, os);

            CountDownLatch latch = peerConnectedLatches.get(peerId);
            if (latch != null) {
                latch.countDown();
            }

            try {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                sseStreams.remove(peerId, os);
                try {
                    exchange.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void handlePostEvents(HttpExchange exchange) throws IOException {
            eventsPosted.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            EventMessageDto event = objectMapper.readValue(body, EventMessageDto.class);

            Long recipientId = event.recipientId();
            if (recipientId != null) {
                sendSseToPeer(recipientId, event);
            } else {
                for (Map.Entry<Long, OutputStream> entry : sseStreams.entrySet()) {
                    if (!entry.getKey().equals(event.senderId())) {
                        sendSseToPeer(entry.getKey(), event);
                    }
                }
            }

            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private synchronized void sendSseToPeer(long peerId, EventMessageDto event) {
            OutputStream os = sseStreams.get(peerId);
            if (os != null) {
                try {
                    String json = objectMapper.writeValueAsString(event);
                    String line = "data: " + json + "\n\n";
                    os.write(line.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                }
            }
        }

        private void respond(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length > 0 ? bytes.length : -1);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.close();
            }
        }

        @Override
        public void close() {
            stop();
        }

        public void stop() {
            if (running.compareAndSet(true, false)) {
                for (OutputStream os : sseStreams.values()) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                }
                sseStreams.clear();
                if (httpServer != null) {
                    httpServer.stop(0);
                }
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        }
    }
}
