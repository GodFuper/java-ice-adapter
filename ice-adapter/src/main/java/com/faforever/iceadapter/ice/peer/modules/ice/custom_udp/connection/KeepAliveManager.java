package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.connection;

import lombok.Getter;
import lombok.ToString;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages keep-alive packets and idle timeout detection.
 */
@Getter
@ToString
public class KeepAliveManager {

    private static final long DEFAULT_KEEP_ALIVE_INTERVAL_MS = 10000; // 10 seconds
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 30000; // 30 seconds

    private final ScheduledExecutorService executor;
    private final Runnable onKeepAlive;
    private final Runnable onIdleTimeout;
    private final long keepAliveIntervalMs;
    private final long idleTimeoutMs;
    private volatile long lastActivityTime;
    private volatile ScheduledFuture<?> keepAliveTask;

    private volatile ConnectionState state = ConnectionState.IDLE;

    private final CopyOnWriteArrayList<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener for connection state changes.
     */
    public void addStateListener(Consumer<ConnectionState> listener) {
        stateListeners.add(listener);
    }

    /**
     * Remove a previously registered state listener.
     */
    public void removeStateListener(Consumer<ConnectionState> listener) {
        stateListeners.remove(listener);
    }

    /**
     * Set the connection state and notify all listeners.
     */
    public void setState(ConnectionState state) {
        if (this.state != state) {
            this.state = state;
            stateListeners.forEach(listener -> listener.accept(state));
        }
    }

    public KeepAliveManager(ScheduledExecutorService executor, Runnable onKeepAlive, Runnable onIdleTimeout) {
        this(executor, onKeepAlive, onIdleTimeout, DEFAULT_KEEP_ALIVE_INTERVAL_MS, DEFAULT_IDLE_TIMEOUT_MS);
    }

    public KeepAliveManager(
            ScheduledExecutorService executor,
            Runnable onKeepAlive,
            Runnable onIdleTimeout,
            long keepAliveIntervalMs,
            long idleTimeoutMs) {
        this.executor = executor;
        this.onKeepAlive = onKeepAlive;
        this.onIdleTimeout = onIdleTimeout;
        this.keepAliveIntervalMs = keepAliveIntervalMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Start the keep-alive scheduler.
     */
    public void start() {
        if (keepAliveTask != null) {
            return;
        }
        keepAliveTask = executor.scheduleAtFixedRate(
                this::checkKeepAlive, keepAliveIntervalMs, keepAliveIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the keep-alive scheduler.
     */
    public void stop() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
    }

    private void checkKeepAlive() {
        long idleTime = System.currentTimeMillis() - lastActivityTime;
        if (idleTime >= idleTimeoutMs) {
            setState(ConnectionState.DISCONNECTED);
            onIdleTimeout.run();
        } else if (state == ConnectionState.ACTIVE) {
            // Send keep-alive if we haven't sent data recently
            if (idleTime >= keepAliveIntervalMs / 2) {
                onKeepAlive.run();
            }
        }
    }

    /**
     * Record activity (received or sent data).
     * Transitions state from IDLE to ACTIVE on first activity.
     */
    public void recordActivity() {
        this.lastActivityTime = System.currentTimeMillis();
        if (this.state == ConnectionState.IDLE) {
            setState(ConnectionState.ACTIVE);
        }
    }

    /**
     * Get idle time in milliseconds.
     */
    public long getIdleTimeMs() {
        return System.currentTimeMillis() - lastActivityTime;
    }

    /**
     * Check if we should send a keep-alive packet.
     */
    public boolean shouldSendKeepAlive() {
        return state == ConnectionState.ACTIVE
                && (System.currentTimeMillis() - lastActivityTime) >= keepAliveIntervalMs;
    }
}
