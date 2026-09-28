package com.faforever.iceadapter.icebreaker;

import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IcebreakerSseListener implements AutoCloseable {

    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    public static final Duration DEFAULT_RESET_BACKOFF_THRESHOLD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Getter
    private final String baseUrl;

    @Getter
    private final long gameId;

    private final Supplier<String> sessionTokenSupplier;
    private final String hmac;
    private final Consumer<EventMessageDto> onEvent;
    private final Consumer<Throwable> onError;
    private final Consumer<Boolean> onConnectionStateChanged;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Getter
    private final Duration initialBackoff;

    @Getter
    private final Duration maxBackoff;

    @Getter
    private final Duration resetBackoffThreshold;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler;

    @Getter
    private volatile Duration currentBackoff;

    private volatile ScheduledFuture<?> reconnectTask;
    private volatile CompletableFuture<HttpResponse<Stream<String>>> activeRequestFuture;
    private volatile Stream<String> activeStream;
    private volatile Thread readerThread;

    public IcebreakerSseListener(
            String baseUrl,
            long gameId,
            Supplier<String> sessionTokenSupplier,
            String hmac,
            Consumer<EventMessageDto> onEvent,
            Consumer<Throwable> onError,
            Consumer<Boolean> onConnectionStateChanged,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration resetBackoffThreshold) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.gameId = gameId;
        this.sessionTokenSupplier =
                Objects.requireNonNull(sessionTokenSupplier, "sessionTokenSupplier must not be null");
        this.hmac = hmac;
        this.onEvent = onEvent;
        this.onError = onError;
        this.onConnectionStateChanged = onConnectionStateChanged;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        this.resetBackoffThreshold =
                Objects.requireNonNull(resetBackoffThreshold, "resetBackoffThreshold must not be null");
        this.currentBackoff = initialBackoff;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "icebreaker-sse-scheduler-" + gameId);
            t.setDaemon(true);
            return t;
        });
    }

    public IcebreakerSseListener(
            String baseUrl,
            long gameId,
            String sessionToken,
            String hmac,
            Consumer<EventMessageDto> onEvent,
            Consumer<Throwable> onError,
            Consumer<Boolean> onConnectionStateChanged,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration resetBackoffThreshold) {
        this(
                baseUrl,
                gameId,
                () -> sessionToken,
                hmac,
                onEvent,
                onError,
                onConnectionStateChanged,
                httpClient,
                objectMapper,
                initialBackoff,
                maxBackoff,
                resetBackoffThreshold);
    }

    public IcebreakerSseListener(
            String baseUrl,
            long gameId,
            Supplier<String> sessionTokenSupplier,
            String hmac,
            Consumer<EventMessageDto> onEvent,
            Consumer<Throwable> onError,
            Consumer<Boolean> onConnectionStateChanged) {
        this(
                baseUrl,
                gameId,
                sessionTokenSupplier,
                hmac,
                onEvent,
                onError,
                onConnectionStateChanged,
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                new ObjectMapper(),
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF,
                DEFAULT_RESET_BACKOFF_THRESHOLD);
    }

    public IcebreakerSseListener(
            String baseUrl,
            long gameId,
            Supplier<String> sessionTokenSupplier,
            String hmac,
            Consumer<EventMessageDto> onEvent) {
        this(baseUrl, gameId, sessionTokenSupplier, hmac, onEvent, null, null);
    }

    public IcebreakerSseListener(
            String baseUrl, long gameId, String sessionToken, String hmac, Consumer<EventMessageDto> onEvent) {
        this(baseUrl, gameId, () -> sessionToken, hmac, onEvent);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> getHmac() {
        return Optional.ofNullable(hmac);
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("IcebreakerSseListener is closed and cannot be started");
        }
        if (running.compareAndSet(false, true)) {
            log.info("Starting IcebreakerSseListener for game {}", gameId);
            currentBackoff = initialBackoff;
            scheduler.execute(this::connect);
        }
    }

    public void stop() {
        close();
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            running.set(false);
            log.info("Closing IcebreakerSseListener for game {}", gameId);

            if (reconnectTask != null) {
                reconnectTask.cancel(true);
            }
            if (activeRequestFuture != null) {
                activeRequestFuture.cancel(true);
            }
            if (activeStream != null) {
                try {
                    activeStream.close();
                } catch (Exception ignored) {
                }
            }
            if (readerThread != null && readerThread != Thread.currentThread()) {
                readerThread.interrupt();
            }

            boolean wasConnected = connected.getAndSet(false);
            if (wasConnected) {
                notifyConnectionStateChanged(false);
            }

            scheduler.shutdownNow();
        }
    }

    private void connect() {
        if (!running.get()) {
            return;
        }

        String token = null;
        try {
            token = sessionTokenSupplier != null ? sessionTokenSupplier.get() : null;
        } catch (Exception e) {
            log.warn("Failed to retrieve session token for game {}: {}", gameId, e.getMessage());
        }

        if (token == null || token.isBlank()) {
            log.warn("Session token is not available for game {}. Reconnecting later.", gameId);
            handleDisconnectOrError(new IllegalStateException("Session token is not available"), 0);
            return;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/game/" + gameId + "/events"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .GET();

        if (hmac != null && !hmac.isBlank()) {
            requestBuilder.header("X-HMAC", hmac);
        }

        HttpRequest request = requestBuilder.build();
        CompletableFuture<HttpResponse<Stream<String>>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        this.activeRequestFuture = future;

        future.whenComplete((response, throwable) -> {
            if (!running.get()) {
                if (response != null) {
                    try {
                        response.body().close();
                    } catch (Exception ignored) {
                    }
                }
                return;
            }

            if (throwable != null) {
                log.warn("Failed to establish SSE connection for game {}: {}", gameId, throwable.getMessage());
                handleDisconnectOrError(throwable, 0);
                return;
            }

            if (response.statusCode() != 200) {
                try {
                    response.body().close();
                } catch (Exception ignored) {
                }
                log.warn("SSE connection rejected for game {}: HTTP {}", gameId, response.statusCode());
                handleDisconnectOrError(
                        new IcebreakerApiException(response.statusCode(), "HTTP " + response.statusCode()), 0);
                return;
            }

            // Successfully connected
            connected.set(true);
            notifyConnectionStateChanged(true);
            long connectedAt = System.currentTimeMillis();

            readerThread = new Thread(
                    () -> {
                        Stream<String> stream = response.body();
                        activeStream = stream;
                        StringBuilder dataBuffer = new StringBuilder();
                        try (stream) {
                            stream.forEach(line -> {
                                if (!running.get()) {
                                    return;
                                }
                                processLine(line, dataBuffer);
                            });
                        } catch (Exception e) {
                            if (running.get()) {
                                log.debug("SSE stream ended with error for game {}: {}", gameId, e.getMessage());
                            }
                        } finally {
                            activeStream = null;
                            long durationMs = System.currentTimeMillis() - connectedAt;
                            handleDisconnectOrError(null, durationMs);
                        }
                    },
                    "icebreaker-sse-reader-" + gameId);
            readerThread.setDaemon(true);
            readerThread.start();
        });
    }

    private void processLine(String line, StringBuilder dataBuffer) {
        if (line.startsWith(":")) {
            // SSE comment (e.g. keepalive/ping), ignore
            return;
        }

        if (line.isEmpty()) {
            // End of event block
            if (!dataBuffer.isEmpty()) {
                String payload = dataBuffer.toString().trim();
                dataBuffer.setLength(0);
                dispatchEvent(payload);
            }
            return;
        }

        if (line.startsWith("data:")) {
            String content = line.substring(5);
            if (content.startsWith(" ")) {
                content = content.substring(1);
            }
            dataBuffer.append(content).append("\n");
        }
    }

    private void dispatchEvent(String payload) {
        try {
            EventMessageDto event = objectMapper.readValue(payload, EventMessageDto.class);
            if (onEvent != null) {
                onEvent.accept(event);
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize SSE event for game {}: {}", gameId, payload, e);
            notifyError(e);
        }
    }

    private synchronized void handleDisconnectOrError(Throwable error, long connectionDurationMs) {
        boolean wasConnected = connected.getAndSet(false);

        if (!running.get() || closed.get()) {
            if (wasConnected) {
                notifyConnectionStateChanged(false);
            }
            if (error != null) {
                notifyError(error);
            }
            return;
        }

        if (connectionDurationMs >= resetBackoffThreshold.toMillis()) {
            currentBackoff = initialBackoff;
        }

        long delayMs = currentBackoff.toMillis();
        log.info(
                "SSE disconnected for game {}. Reconnecting in {} ms (connected duration: {} ms)",
                gameId,
                delayMs,
                connectionDurationMs);

        currentBackoff = Duration.ofMillis(Math.min(maxBackoff.toMillis(), delayMs * 2));

        if (wasConnected) {
            notifyConnectionStateChanged(false);
        }

        if (error != null) {
            notifyError(error);
        }

        try {
            reconnectTask = scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Scheduler rejected reconnect task: {}", e.getMessage());
        }
    }

    private void notifyConnectionStateChanged(boolean isConnected) {
        if (onConnectionStateChanged != null) {
            try {
                onConnectionStateChanged.accept(isConnected);
            } catch (Exception e) {
                log.error("Error in onConnectionStateChanged callback", e);
            }
        }
    }

    private void notifyError(Throwable throwable) {
        if (onError != null) {
            try {
                onError.accept(throwable);
            } catch (Exception e) {
                log.error("Error in onError callback", e);
            }
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

    public static class Builder {
        private String baseUrl;
        private long gameId;
        private Supplier<String> sessionTokenSupplier;
        private String hmac;
        private Consumer<EventMessageDto> onEvent;
        private Consumer<Throwable> onError;
        private Consumer<Boolean> onConnectionStateChanged;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private Duration resetBackoffThreshold = DEFAULT_RESET_BACKOFF_THRESHOLD;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder gameId(long gameId) {
            this.gameId = gameId;
            return this;
        }

        public Builder sessionToken(String sessionToken) {
            this.sessionTokenSupplier = () -> sessionToken;
            return this;
        }

        public Builder sessionTokenSupplier(Supplier<String> sessionTokenSupplier) {
            this.sessionTokenSupplier = sessionTokenSupplier;
            return this;
        }

        public Builder hmac(String hmac) {
            this.hmac = hmac;
            return this;
        }

        public Builder onEvent(Consumer<EventMessageDto> onEvent) {
            this.onEvent = onEvent;
            return this;
        }

        public Builder onError(Consumer<Throwable> onError) {
            this.onError = onError;
            return this;
        }

        public Builder onConnectionStateChanged(Consumer<Boolean> onConnectionStateChanged) {
            this.onConnectionStateChanged = onConnectionStateChanged;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder resetBackoffThreshold(Duration resetBackoffThreshold) {
            this.resetBackoffThreshold = resetBackoffThreshold;
            return this;
        }

        public IcebreakerSseListener build() {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build();
            }
            if (objectMapper == null) {
                objectMapper = new ObjectMapper();
            }
            return new IcebreakerSseListener(
                    baseUrl,
                    gameId,
                    sessionTokenSupplier,
                    hmac,
                    onEvent,
                    onError,
                    onConnectionStateChanged,
                    httpClient,
                    objectMapper,
                    initialBackoff,
                    maxBackoff,
                    resetBackoffThreshold);
        }
    }
}
