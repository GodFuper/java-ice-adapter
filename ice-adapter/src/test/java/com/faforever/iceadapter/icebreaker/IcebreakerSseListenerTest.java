package com.faforever.iceadapter.icebreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IcebreakerSseListenerTest {

    private HttpServer server;
    private String serverUrl;
    private ObjectMapper objectMapper;
    private IcebreakerSseListener listener;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        serverUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length > 0 ? bytes.length : -1);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @Test
    void successfulReceiveEvents_connected_candidates_peerClosing() throws Exception {
        CountDownLatch headersLatch = new CountDownLatch(1);
        CountDownLatch eventsLatch = new CountDownLatch(3);
        List<EventMessageDto> receivedEvents = new CopyOnWriteArrayList<>();
        List<Boolean> connectionStates = new CopyOnWriteArrayList<>();

        server.createContext("/session/game/42/events", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("Bearer test-token-123", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("test-hmac", exchange.getRequestHeaders().getFirst("X-HMAC"));
            headersLatch.countDown();

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                String event1 = "data: {\"eventType\":\"connected\",\"gameId\":42,\"senderId\":10}\n\n";
                os.write(event1.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String event2 = "data: {\"eventType\":\"candidates\",\"gameId\":42,\"senderId\":10,\"recipientId\":20,"
                        + "\"session\":{\"type\":\"offer\",\"sdp\":\"v=0...\"},\"candidates\":[]}\n\n";
                os.write(event2.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String event3 =
                        "data: {\"eventType\":\"peerClosing\",\"gameId\":42,\"senderId\":10,\"recipientId\":20}\n\n";
                os.write(event3.getBytes(StandardCharsets.UTF_8));
                os.flush();

                // Keep stream alive until latch triggered
                eventsLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token-123")
                .hmac("test-hmac")
                .onEvent(event -> {
                    receivedEvents.add(event);
                    eventsLatch.countDown();
                })
                .onConnectionStateChanged(connectionStates::add)
                .objectMapper(objectMapper)
                .build();

        listener.start();

        assertTrue(headersLatch.await(5, TimeUnit.SECONDS), "Request headers must be verified");
        assertTrue(eventsLatch.await(5, TimeUnit.SECONDS), "All 3 events must be received");

        assertEquals(3, receivedEvents.size());
        assertInstanceOf(EventMessageDto.Connected.class, receivedEvents.get(0));
        assertEquals(42L, receivedEvents.get(0).gameId());
        assertEquals(10L, receivedEvents.get(0).senderId());

        assertInstanceOf(EventMessageDto.Candidates.class, receivedEvents.get(1));
        EventMessageDto.Candidates candidates = (EventMessageDto.Candidates) receivedEvents.get(1);
        assertEquals(42L, candidates.gameId());
        assertEquals(10L, candidates.senderId());
        assertEquals(20L, candidates.recipientId());
        assertEquals("offer", candidates.session().get("type").asText());

        assertInstanceOf(EventMessageDto.PeerClosing.class, receivedEvents.get(2));
        assertEquals(42L, receivedEvents.get(2).gameId());
        assertEquals(10L, receivedEvents.get(2).senderId());
        assertEquals(20L, receivedEvents.get(2).recipientId());

        assertTrue(listener.isConnected());
        assertTrue(connectionStates.contains(true));
    }

    @Test
    void handlesKeepAliveCommentsAndWhitespace() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<EventMessageDto> receivedEvents = new CopyOnWriteArrayList<>();

        server.createContext("/session/game/42/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                String payload = ":ping\n"
                        + ":keep-alive\n\n"
                        + "data: {\"eventType\":\"connected\",\"gameId\":42,\"senderId\":10}\n\n"
                        + ":comment after event\n\n";
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.flush();

                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token")
                .onEvent(event -> {
                    receivedEvents.add(event);
                    latch.countDown();
                })
                .objectMapper(objectMapper)
                .build();

        listener.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, receivedEvents.size());
        assertInstanceOf(EventMessageDto.Connected.class, receivedEvents.get(0));
    }

    @Test
    void autoReconnectAfterConnectionDrop_deliversSubsequentEvents() throws Exception {
        AtomicInteger connectionAttempt = new AtomicInteger();
        CountDownLatch firstEventLatch = new CountDownLatch(1);
        CountDownLatch secondEventLatch = new CountDownLatch(1);
        List<EventMessageDto> receivedEvents = new CopyOnWriteArrayList<>();
        List<Boolean> connectionStates = new CopyOnWriteArrayList<>();

        server.createContext("/session/game/42/events", exchange -> {
            int attempt = connectionAttempt.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                if (attempt == 1) {
                    // Send first event and close stream
                    String event1 = "data: {\"eventType\":\"connected\",\"gameId\":42,\"senderId\":10}\n\n";
                    os.write(event1.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    // Close response stream abruptly
                } else {
                    // Send second event on reconnect
                    String event2 =
                            "data: {\"eventType\":\"peerClosing\",\"gameId\":42,\"senderId\":10,\"recipientId\":20}\n\n";
                    os.write(event2.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    secondEventLatch.await(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token")
                .initialBackoff(Duration.ofMillis(30))
                .maxBackoff(Duration.ofMillis(100))
                .onEvent(event -> {
                    receivedEvents.add(event);
                    if (event instanceof EventMessageDto.Connected) {
                        firstEventLatch.countDown();
                    } else if (event instanceof EventMessageDto.PeerClosing) {
                        secondEventLatch.countDown();
                    }
                })
                .onConnectionStateChanged(connectionStates::add)
                .objectMapper(objectMapper)
                .build();

        listener.start();

        assertTrue(firstEventLatch.await(5, TimeUnit.SECONDS), "First event must be received");
        assertTrue(secondEventLatch.await(5, TimeUnit.SECONDS), "Second event must be received after reconnect");

        assertEquals(2, receivedEvents.size());
        assertInstanceOf(EventMessageDto.Connected.class, receivedEvents.get(0));
        assertInstanceOf(EventMessageDto.PeerClosing.class, receivedEvents.get(1));
        assertTrue(connectionAttempt.get() >= 2, "Should have connected at least twice");

        // Should have transitioned: connected (true) -> disconnected (false) -> connected (true)
        assertTrue(connectionStates.contains(true));
        assertTrue(connectionStates.contains(false));
    }

    @Test
    void reconnectOnServerError_thenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch successLatch = new CountDownLatch(1);
        List<EventMessageDto> receivedEvents = new CopyOnWriteArrayList<>();

        server.createContext("/session/game/42/events", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                respond(exchange, 503, "Service Unavailable");
            } else {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    String event = "data: {\"eventType\":\"connected\",\"gameId\":42,\"senderId\":10}\n\n";
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    successLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token")
                .initialBackoff(Duration.ofMillis(30))
                .maxBackoff(Duration.ofMillis(100))
                .onEvent(event -> {
                    receivedEvents.add(event);
                    successLatch.countDown();
                })
                .objectMapper(objectMapper)
                .build();

        listener.start();

        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Event must be received on second attempt");
        assertEquals(1, receivedEvents.size());
        assertEquals(2, attempts.get());
    }

    @Test
    void backoffResetAfterStableConnection() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch initialFailuresLatch = new CountDownLatch(2);
        CountDownLatch stableDisconnectLatch = new CountDownLatch(1);

        server.createContext("/session/game/42/events", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                // First 2 attempts fail immediately -> escalates backoff
                respond(exchange, 500, "Internal error");
            } else if (attempt == 3) {
                // Third attempt connects and stays stable for 150 ms (> threshold 100 ms)
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    String event = "data: {\"eventType\":\"connected\",\"gameId\":42,\"senderId\":10}\n\n";
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
            } else {
                respond(exchange, 500, "After stable");
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token")
                .initialBackoff(Duration.ofMillis(40))
                .maxBackoff(Duration.ofMillis(300))
                .resetBackoffThreshold(Duration.ofMillis(100))
                .onError(err -> {
                    if (attempts.get() <= 2) {
                        initialFailuresLatch.countDown();
                    }
                })
                .onConnectionStateChanged(state -> {
                    if (!state && attempts.get() >= 3) {
                        stableDisconnectLatch.countDown();
                    }
                })
                .objectMapper(objectMapper)
                .build();

        listener.start();

        assertTrue(initialFailuresLatch.await(5, TimeUnit.SECONDS), "Initial failures must occur");
        // After attempt 1 (40 -> 80) and attempt 2 (80 -> 160), backoff is 160 ms
        assertEquals(Duration.ofMillis(160), listener.getCurrentBackoff());

        assertTrue(stableDisconnectLatch.await(5, TimeUnit.SECONDS), "Stable connection disconnect must complete");

        // Attempt 3 was stable (> 100ms), so backoff reset to initialBackoff (40ms) and doubled to 80ms for the next
        // retry
        assertEquals(Duration.ofMillis(80), listener.getCurrentBackoff());
    }

    @Test
    void gracefulStopAndClose() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch disconnectedLatch = new CountDownLatch(1);

        server.createContext("/session/game/42/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(":keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                // Block until server stopped or client closes connection
                Thread.sleep(10000);
            } catch (Exception ignored) {
            }
        });

        listener = IcebreakerSseListener.builder()
                .baseUrl(serverUrl)
                .gameId(42L)
                .sessionToken("test-token")
                .onConnectionStateChanged(state -> {
                    if (state) {
                        connectedLatch.countDown();
                    } else {
                        disconnectedLatch.countDown();
                    }
                })
                .objectMapper(objectMapper)
                .build();

        listener.start();
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Must establish connection");
        assertTrue(listener.isConnected());
        assertTrue(listener.isRunning());

        listener.stop();

        assertFalse(listener.isRunning());
        assertFalse(listener.isConnected());
        assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS), "Must notify disconnected on stop");
    }
}
