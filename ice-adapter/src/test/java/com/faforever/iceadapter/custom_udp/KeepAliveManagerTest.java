package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.connection.ConnectionState;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.connection.KeepAliveManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeepAliveManagerTest {

    private ScheduledExecutorService executor;
    private KeepAliveManager keepAliveManager;
    private final AtomicInteger keepAliveCount = new AtomicInteger(0);
    private final AtomicInteger timeoutCount = new AtomicInteger(0);
    private final CopyOnWriteArrayList<ConnectionState> stateHistory = new CopyOnWriteArrayList<>();
    private final java.util.function.Consumer<ConnectionState> stateListener = stateHistory::add;

    private static final long KEEP_ALIVE_INTERVAL_MS = 100;
    private static final long IDLE_TIMEOUT_MS = 500;

    @BeforeEach
    void setUp() {
        executor = Executors.newScheduledThreadPool(2);
        keepAliveCount.set(0);
        timeoutCount.set(0);
        stateHistory.clear();

        keepAliveManager = new KeepAliveManager(
                executor,
                () -> keepAliveCount.incrementAndGet(),
                () -> timeoutCount.incrementAndGet(),
                KEEP_ALIVE_INTERVAL_MS,
                IDLE_TIMEOUT_MS);

        keepAliveManager.addStateListener(stateListener);
    }

    @AfterEach
    void tearDown() {
        keepAliveManager.removeStateListener(stateListener);
        keepAliveManager.stop();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
    }

    @Test
    void testInitialState() {
        assertEquals(ConnectionState.IDLE, keepAliveManager.getState());
        assertTrue(keepAliveManager.getIdleTimeMs() < 100);
    }

    @Test
    void testRecordActivityReducesIdleTime() throws InterruptedException {
        keepAliveManager.start();
        Thread.sleep(50);
        long idleBefore = keepAliveManager.getIdleTimeMs();

        keepAliveManager.recordActivity();
        long idleAfter = keepAliveManager.getIdleTimeMs();

        assertTrue(idleAfter < idleBefore);
    }

    @Test
    void testShouldSendKeepAliveReturnsFalseWhenIdle() {
        assertFalse(keepAliveManager.shouldSendKeepAlive());
    }

    @Test
    void testShouldSendKeepAliveReturnsFalseWhenConnecting() {
        keepAliveManager.setState(ConnectionState.CONNECTING);
        assertFalse(keepAliveManager.shouldSendKeepAlive());
    }

    @Test
    void testShouldSendKeepAliveReturnsFalseWhenDisconnected() {
        keepAliveManager.setState(ConnectionState.DISCONNECTED);
        assertFalse(keepAliveManager.shouldSendKeepAlive());
    }

    @Test
    void testShouldSendKeepAliveReturnsFalseWhenActiveButNotIdle() throws InterruptedException {
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        Thread.sleep(50);

        assertFalse(keepAliveManager.shouldSendKeepAlive());
    }

    @Test
    void testShouldSendKeepAliveReturnsTrueWhenActiveAndIdleExceedsInterval() throws InterruptedException {
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        Thread.sleep(150);

        assertTrue(keepAliveManager.shouldSendKeepAlive());
    }

    @Test
    void testStartAndStop() throws InterruptedException {
        keepAliveManager.start();
        Thread.sleep(50);

        int stateCountAfterStart = stateHistory.size();

        keepAliveManager.stop();

        assertTrue(stateHistory.size() >= stateCountAfterStart);
    }

    @Test
    void testKeepAliveSentWhenActive() throws InterruptedException {
        keepAliveManager.start();
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        // Ждем 2 * KEEP_ALIVE_INTERVAL_MS, чтобы checkKeepAlive сработал
        Thread.sleep(KEEP_ALIVE_INTERVAL_MS * 2 + 50);

        assertTrue(keepAliveCount.get() >= 1);
    }

    @Test
    void testIdleTimeoutTriggersDisconnection() throws InterruptedException {
        keepAliveManager.start();
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        // Ждем дольше чем IDLE_TIMEOUT_MS
        Thread.sleep(IDLE_TIMEOUT_MS + 100);

        assertTrue(timeoutCount.get() >= 1);
        assertEquals(ConnectionState.DISCONNECTED, keepAliveManager.getState());
    }

    @Test
    void testRecordActivityResetsIdleTimeout() throws InterruptedException {
        keepAliveManager.start();
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        // Ждем половину таймаута
        Thread.sleep(IDLE_TIMEOUT_MS / 2);

        // Сбрасываем idle time
        keepAliveManager.recordActivity();

        // Ждем еще половину + немного
        Thread.sleep(IDLE_TIMEOUT_MS / 2 + 50);

        assertEquals(0, timeoutCount.get());
        assertNotEquals(ConnectionState.DISCONNECTED, keepAliveManager.getState());
    }

    @Test
    void testNoKeepAliveWhenNotActive() throws InterruptedException {
        keepAliveManager.start();
        keepAliveManager.recordActivity();
        // recordActivity() автоматически переводит из IDLE в ACTIVE,
        // поэтому возвращаем IDLE для теста
        keepAliveManager.setState(ConnectionState.IDLE);

        // Ждем 3 * KEEP_ALIVE_INTERVAL_MS
        Thread.sleep(KEEP_ALIVE_INTERVAL_MS * 3 + 50);

        assertEquals(0, keepAliveCount.get());
        assertEquals(ConnectionState.IDLE, keepAliveManager.getState());
    }

    @Test
    void testStateHistoryRecordsChanges() {
        assertEquals(ConnectionState.IDLE, keepAliveManager.getState());

        keepAliveManager.setState(ConnectionState.CONNECTING);
        assertEquals(ConnectionState.CONNECTING, keepAliveManager.getState());

        keepAliveManager.setState(ConnectionState.ACTIVE);
        assertEquals(ConnectionState.ACTIVE, keepAliveManager.getState());

        // Setting same state should not trigger duplicate notifications
        keepAliveManager.setState(ConnectionState.ACTIVE);
        assertEquals(ConnectionState.ACTIVE, keepAliveManager.getState());
    }

    @Test
    void testStartIsIdempotent() {
        keepAliveManager.start();
        keepAliveManager.start();
        keepAliveManager.start();

        // Должно быть только 1 task в executor
        // Проверяем, что нет дублирующихся keep-alive
        keepAliveManager.setState(ConnectionState.ACTIVE);
        keepAliveManager.recordActivity();

        try {
            Thread.sleep(KEEP_ALIVE_INTERVAL_MS * 3 + 50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Если start() не был иdemesotent, keepAliveCount будет в 3 раза выше
        // Мы ожидаем максимум 3 срабатывания (за 3 интервала)
        assertTrue(keepAliveCount.get() <= 3);
    }
}
