package client.pioneer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import logging.Logger;
import lombok.Getter;

/**
 * Built-in mock Icebreaker HTTP server.
 * Provides endpoints for faf-adapter: session token, game configuration,
 * SSE events stream, and candidate exchange.
 */
public class MockIcebreakerHttpServer {

    @Getter
    private final int port;

    @Getter
    private final String url;

    private HttpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService keepaliveScheduler;
    private java.util.function.Consumer<String> onEventListener;

    public void setOnEventListener(java.util.function.Consumer<String> listener) {
        this.onEventListener = listener;
    }

    public void sendSseEvent(String eventJson) {
        byte[] bytes = ("data: " + eventJson + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try {
                os.write(bytes);
                os.flush();
            } catch (IOException e) {
                sseClients.remove(os);
            }
        }
    }

    public MockIcebreakerHttpServer(int port) {
        this.port = port;
        this.url = "http://127.0.0.1:" + port;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // 1. Session token
        server.createContext("/session/token", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 200, "{\"jwt\":\"mock-jwt-session-token\"}");
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });

        // 2. Session Game info & endpoints
        server.createContext("/session/game", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.endsWith("/events")) {
                if ("POST".equalsIgnoreCase(method)) {
                    // Read event body
                    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);
                    Logger.debug("MockIcebreaker received POST event: " + body);

                    if (onEventListener != null) {
                        try {
                            onEventListener.accept(body);
                        } catch (Exception e) {
                            Logger.error("Error in onEventListener", e);
                        }
                    }

                    // Pioneer expects 204 No Content
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                } else {
                    // SSE Stream
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.getResponseHeaders().set("Connection", "keep-alive");
                    exchange.sendResponseHeaders(200, 0); // chunked

                    OutputStream os = exchange.getResponseBody();
                    os.write(": initial-connect\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();

                    sseClients.add(os);
                    Logger.info("SSE client connected to MockIcebreaker");

                    try {
                        while (running.get() && sseClients.contains(os)) {
                            Thread.sleep(500);
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        sseClients.remove(os);
                        try {
                            os.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } else if (path.endsWith("/addresses") || path.endsWith("/address") || path.endsWith("/logs")) {
                // Accept POST address/addresses/logs with 204 No Content
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if ("GET".equalsIgnoreCase(method)) {
                // Game session details
                String gId = path.substring(path.lastIndexOf('/') + 1);
                String responseJson = "{\"id\":\"" + gId + "\",\"forceRelay\":false,\"servers\":[]}";
                sendJsonResponse(exchange, 200, responseJson);
            } else {
                sendJsonResponse(exchange, 200, "{\"status\":\"ok\"}");
            }
        });

        server.start();
        running.set(true);

        // Start SSE keepalive sender
        keepaliveScheduler = Executors.newSingleThreadScheduledExecutor();
        keepaliveScheduler.scheduleAtFixedRate(() -> {
            for (OutputStream os : sseClients) {
                try {
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    sseClients.remove(os);
                }
            }
        }, 1, 2, TimeUnit.SECONDS);

        Logger.info("MockIcebreakerHttpServer started on " + url);
    }

    public synchronized void stop() {
        running.set(false);

        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdownNow();
            keepaliveScheduler = null;
        }

        for (OutputStream os : sseClients) {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
        sseClients.clear();

        if (server != null) {
            server.stop(0);
            server = null;
        }

        Logger.info("MockIcebreakerHttpServer stopped");
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
