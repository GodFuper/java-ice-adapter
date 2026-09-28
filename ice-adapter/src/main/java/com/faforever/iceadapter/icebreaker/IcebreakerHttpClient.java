package com.faforever.iceadapter.icebreaker;

import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.faforever.iceadapter.icebreaker.dto.SessionGameResponse;
import com.faforever.iceadapter.icebreaker.dto.SessionTokenRequest;
import com.faforever.iceadapter.icebreaker.dto.SessionTokenResponse;
import com.faforever.iceadapter.icebreaker.util.HmacExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IcebreakerHttpClient implements AutoCloseable {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_INITIAL_RETRY_DELAY = Duration.ofMillis(100);
    private static final int DEFAULT_MAX_RETRIES = 3;

    @Getter
    private final String baseUrl;

    @Getter
    private final String accessToken;

    private final String hmac;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final Duration initialRetryDelay;
    private final int maxRetries;

    @Getter
    @Setter
    private volatile String sessionToken;

    public IcebreakerHttpClient(
            String baseUrl,
            String accessToken,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            Duration initialRetryDelay,
            int maxRetries) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        this.hmac = HmacExtractor.extractHmac(accessToken).orElse(null);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        this.initialRetryDelay = Objects.requireNonNull(initialRetryDelay, "initialRetryDelay must not be null");
        this.maxRetries = maxRetries;
    }

    public IcebreakerHttpClient(String baseUrl, String accessToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this(
                baseUrl,
                accessToken,
                httpClient,
                objectMapper,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_INITIAL_RETRY_DELAY,
                DEFAULT_MAX_RETRIES);
    }

    public IcebreakerHttpClient(String baseUrl, String accessToken, ObjectMapper objectMapper) {
        this(
                baseUrl,
                accessToken,
                HttpClient.newBuilder().connectTimeout(DEFAULT_REQUEST_TIMEOUT).build(),
                objectMapper);
    }

    public IcebreakerHttpClient(String baseUrl, String accessToken) {
        this(baseUrl, accessToken, new ObjectMapper());
    }

    public Optional<String> getHmac() {
        return Optional.ofNullable(hmac);
    }

    public CompletableFuture<String> fetchSessionToken(long gameId) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(new SessionTokenRequest(gameId));
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/token"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (hmac != null) {
            requestBuilder.header("X-HMAC", hmac);
        }

        return httpClient
                .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            SessionTokenResponse tokenResponse =
                                    objectMapper.readValue(response.body(), SessionTokenResponse.class);
                            this.sessionToken = tokenResponse.jwt();
                            return CompletableFuture.completedFuture(tokenResponse.jwt());
                        } catch (JsonProcessingException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }
                    return CompletableFuture.failedFuture(
                            new IcebreakerApiException(response.statusCode(), response.body()));
                });
    }

    public CompletableFuture<SessionGameResponse> fetchGameSession(long gameId) {
        String activeToken = this.sessionToken;
        if (activeToken == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Session token is not set. Call fetchSessionToken first."));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/game/" + gameId))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + activeToken)
                .timeout(requestTimeout)
                .GET();

        if (hmac != null) {
            requestBuilder.header("X-HMAC", hmac);
        }

        return httpClient
                .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            SessionGameResponse gameResponse =
                                    objectMapper.readValue(response.body(), SessionGameResponse.class);
                            return CompletableFuture.completedFuture(gameResponse);
                        } catch (JsonProcessingException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }
                    return CompletableFuture.failedFuture(
                            new IcebreakerApiException(response.statusCode(), response.body()));
                });
    }

    public CompletableFuture<Void> registerAddresses(long gameId) {
        String activeToken = this.sessionToken;
        if (activeToken == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Session token is not set. Call fetchSessionToken first."));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/game/" + gameId + "/addresses"))
                .header("Authorization", "Bearer " + activeToken)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.noBody());

        if (hmac != null) {
            requestBuilder.header("X-HMAC", hmac);
        }

        return httpClient
                .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(null);
                    }
                    log.warn(
                            "Failed to register addresses for game {}: HTTP {} {}",
                            gameId,
                            response.statusCode(),
                            response.body());
                    return CompletableFuture.failedFuture(
                            new IcebreakerApiException(response.statusCode(), response.body()));
                });
    }

    public CompletableFuture<Void> sendEvent(long gameId, EventMessageDto message) {
        String activeToken = this.sessionToken;
        if (activeToken == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Session token is not set. Call fetchSessionToken first."));
        }

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/game/" + gameId + "/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + activeToken)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (hmac != null) {
            requestBuilder.header("X-HMAC", hmac);
        }

        HttpRequest request = requestBuilder.build();
        return sendWithRetry(request, gameId, 1);
    }

    private CompletableFuture<Void> sendWithRetry(HttpRequest request, long gameId, int attempt) {
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    boolean isServerError = (response != null && response.statusCode() >= 500);
                    boolean isNetworkError = (throwable != null);
                    if ((isServerError || isNetworkError) && attempt < maxRetries) {
                        long delayMs = initialRetryDelay.toMillis() * (1L << (attempt - 1));
                        log.warn(
                                "sendEvent failed (attempt {}/{}), retrying in {} ms: {}",
                                attempt,
                                maxRetries,
                                delayMs,
                                throwable != null ? throwable.getMessage() : "HTTP " + response.statusCode());
                        CompletableFuture<Void> retryFuture = new CompletableFuture<>();
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                                .execute(() -> sendWithRetry(request, gameId, attempt + 1)
                                        .whenComplete((res, ex) -> {
                                            if (ex != null) {
                                                retryFuture.completeExceptionally(ex);
                                            } else {
                                                retryFuture.complete(res);
                                            }
                                        }));
                        return retryFuture;
                    }
                    if (throwable != null) {
                        return CompletableFuture.<Void>failedFuture(throwable);
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return CompletableFuture.<Void>failedFuture(
                            new IcebreakerApiException(response.statusCode(), response.body()));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Error closing HttpClient", e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        Objects.requireNonNull(url, "baseUrl must not be null");
        String trimmed = url.strip();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
