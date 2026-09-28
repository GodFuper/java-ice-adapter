package com.faforever.iceadapter.icebreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.faforever.iceadapter.icebreaker.dto.SessionGameResponse;
import com.faforever.iceadapter.icebreaker.dto.SessionTokenRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IcebreakerHttpClientTest {

    private HttpServer server;
    private String serverUrl;
    private ObjectMapper objectMapper;
    private String validJwtWithHmac;
    private String validJwtWithoutHmac;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        serverUrl = "http://127.0.0.1:" + port;

        validJwtWithHmac = createJwt("{\"sub\":\"123\",\"ext\":{\"hmac\":\"secret-hmac-val\"}}");
        validJwtWithoutHmac = createJwt("{\"sub\":\"123\",\"ext\":{}}");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
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
    void fetchSessionToken_success_savesTokenAndReturnsJwt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/session/token", exchange -> {
            requestCount.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(
                    "Bearer " + validJwtWithHmac, exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("secret-hmac-val", exchange.getRequestHeaders().getFirst("X-HMAC"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SessionTokenRequest request = objectMapper.readValue(body, SessionTokenRequest.class);
            assertEquals(42L, request.gameId());

            respond(exchange, 200, "{\"jwt\":\"session-token-abc\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);

        CompletableFuture<String> future = client.fetchSessionToken(42L);
        String token = future.join();

        assertEquals(1, requestCount.get());
        assertEquals("session-token-abc", token);
        assertEquals("session-token-abc", client.getSessionToken());
    }

    @Test
    void fetchSessionToken_withoutHmac_omitsHmacHeader() {
        server.createContext("/session/token", exchange -> {
            assertEquals(
                    "Bearer " + validJwtWithoutHmac,
                    exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("X-HMAC"));
            respond(exchange, 200, "{\"jwt\":\"session-token-no-hmac\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithoutHmac, objectMapper);

        String token = client.fetchSessionToken(42L).join();
        assertEquals("session-token-no-hmac", token);
        assertEquals("session-token-no-hmac", client.getSessionToken());
    }

    @Test
    void fetchSessionToken_serverError_completesExceptionallyWithApiException() {
        server.createContext("/session/token", exchange -> {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);

        CompletableFuture<String> future = client.fetchSessionToken(42L);
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IcebreakerApiException.class, thrown.getCause());
        IcebreakerApiException apiEx = (IcebreakerApiException) thrown.getCause();
        assertEquals(401, apiEx.getStatusCode());
        assertTrue(apiEx.getResponseBody().contains("unauthorized"));
        assertNull(client.getSessionToken());
    }

    @Test
    void fetchGameSession_success_returnsGameSession() {
        server.createContext("/session/game/42", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals(
                    "Bearer active-session-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("secret-hmac-val", exchange.getRequestHeaders().getFirst("X-HMAC"));

            String json =
                    """
                    {
                        "id": "session-42",
                        "forceRelay": true,
                        "servers": [
                            {
                                "id": "srv-1",
                                "urls": ["turn:turn.faforever.com:3478"],
                                "username": "usr",
                                "credential": "pwd"
                            }
                        ]
                    }
                    """;
            respond(exchange, 200, json);
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);
        client.setSessionToken("active-session-token");

        SessionGameResponse response = client.fetchGameSession(42L).join();

        assertNotNull(response);
        assertEquals("session-42", response.id());
        assertTrue(response.forceRelay());
        assertEquals(1, response.servers().size());
        assertEquals("srv-1", response.servers().get(0).id());
        assertEquals(
                List.of("turn:turn.faforever.com:3478"),
                response.servers().get(0).urls());
        assertEquals("usr", response.servers().get(0).username());
        assertEquals("pwd", response.servers().get(0).credential());
    }

    @Test
    void fetchGameSession_withoutSessionToken_completesExceptionally() {
        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);

        CompletableFuture<SessionGameResponse> future = client.fetchGameSession(42L);
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void fetchGameSession_serverError_completesExceptionallyWithApiException() {
        server.createContext("/session/game/42", exchange -> {
            respond(exchange, 404, "{\"error\":\"not found\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);
        client.setSessionToken("active-session-token");

        CompletableFuture<SessionGameResponse> future = client.fetchGameSession(42L);
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IcebreakerApiException.class, thrown.getCause());
        IcebreakerApiException apiEx = (IcebreakerApiException) thrown.getCause();
        assertEquals(404, apiEx.getStatusCode());
    }

    @Test
    void registerAddresses_success() {
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/session/game/42/addresses", exchange -> {
            requestCount.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(
                    "Bearer active-session-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 204, "");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);
        client.setSessionToken("active-session-token");

        client.registerAddresses(42L).join();
        assertEquals(1, requestCount.get());
    }

    @Test
    void registerAddresses_serverError_completesExceptionally() {
        server.createContext("/session/game/42/addresses", exchange -> {
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);
        client.setSessionToken("active-session-token");

        CompletableFuture<Void> future = client.registerAddresses(42L);
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IcebreakerApiException.class, thrown.getCause());
        IcebreakerApiException apiEx = (IcebreakerApiException) thrown.getCause();
        assertEquals(500, apiEx.getStatusCode());
    }

    @Test
    void sendEvent_success() {
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/session/game/42/events", exchange -> {
            requestCount.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(
                    "Bearer active-session-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("secret-hmac-val", exchange.getRequestHeaders().getFirst("X-HMAC"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                EventMessageDto event = objectMapper.readValue(body, EventMessageDto.class);
                assertInstanceOf(EventMessageDto.PeerClosing.class, event);
                assertEquals(42L, event.gameId());
                assertEquals(1L, event.senderId());
                assertEquals(2L, event.recipientId());
            } catch (Exception e) {
                respond(exchange, 400, e.getMessage());
                return;
            }

            respond(exchange, 204, "");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac, objectMapper);
        client.setSessionToken("active-session-token");

        EventMessageDto message = new EventMessageDto.PeerClosing(42L, 1L, 2L);
        client.sendEvent(42L, message).join();

        assertEquals(1, requestCount.get());
    }

    @Test
    void sendEvent_retryOn500ThenSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/session/game/42/events", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                respond(exchange, 500, "{\"error\":\"temporary error\"}");
            } else {
                respond(exchange, 204, "");
            }
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(
                serverUrl,
                validJwtWithHmac,
                HttpClient.newHttpClient(),
                objectMapper,
                Duration.ofSeconds(5),
                Duration.ofMillis(20),
                3);
        client.setSessionToken("active-session-token");

        EventMessageDto message = new EventMessageDto.PeerClosing(42L, 1L, 2L);
        client.sendEvent(42L, message).join();

        assertEquals(2, attempts.get());
    }

    @Test
    void sendEvent_retryExhausted_completesExceptionally() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/session/game/42/events", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"service unavailable\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(
                serverUrl,
                validJwtWithHmac,
                HttpClient.newHttpClient(),
                objectMapper,
                Duration.ofSeconds(5),
                Duration.ofMillis(20),
                3);
        client.setSessionToken("active-session-token");

        EventMessageDto message = new EventMessageDto.PeerClosing(42L, 1L, 2L);
        CompletableFuture<Void> future = client.sendEvent(42L, message);

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IcebreakerApiException.class, thrown.getCause());
        IcebreakerApiException apiEx = (IcebreakerApiException) thrown.getCause();
        assertEquals(503, apiEx.getStatusCode());
        assertEquals(3, attempts.get());
    }

    @Test
    void sendEvent_clientError400_doesNotRetry() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/session/game/42/events", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"bad request\"}");
        });

        IcebreakerHttpClient client = new IcebreakerHttpClient(
                serverUrl,
                validJwtWithHmac,
                HttpClient.newHttpClient(),
                objectMapper,
                Duration.ofSeconds(5),
                Duration.ofMillis(20),
                3);
        client.setSessionToken("active-session-token");

        EventMessageDto message = new EventMessageDto.PeerClosing(42L, 1L, 2L);
        CompletableFuture<Void> future = client.sendEvent(42L, message);

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IcebreakerApiException.class, thrown.getCause());
        IcebreakerApiException apiEx = (IcebreakerApiException) thrown.getCause();
        assertEquals(400, apiEx.getStatusCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void registerAddresses_withoutSessionToken_completesExceptionally() {
        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac);

        CompletableFuture<Void> future = client.registerAddresses(42L);
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void sendEvent_withoutSessionToken_completesExceptionally() {
        IcebreakerHttpClient client = new IcebreakerHttpClient(serverUrl, validJwtWithHmac);

        CompletableFuture<Void> future = client.sendEvent(42L, new EventMessageDto.Connected(42L, 1L, null));
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void constructor_normalizesBaseUrl() {
        IcebreakerHttpClient client1 = new IcebreakerHttpClient("http://localhost:8080/", validJwtWithHmac);
        assertEquals("http://localhost:8080", client1.getBaseUrl());

        IcebreakerHttpClient client2 = new IcebreakerHttpClient("http://localhost:8080", validJwtWithHmac);
        assertEquals("http://localhost:8080", client2.getBaseUrl());
    }
}
