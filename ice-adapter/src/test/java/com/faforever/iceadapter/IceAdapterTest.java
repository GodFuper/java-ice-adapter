package com.faforever.iceadapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class IceAdapterTest {

    private IceAdapter adapter;

    @BeforeEach
    public void setUp() {
        IceAdapter.INSTANCE = null; // Сброс синглтона перед каждым тестом
        System.setProperty("java.awt.headless", "true"); // Чтобы TrayIcon не падал
    }

    @AfterEach
    public void tearDown() {
        if (IceAdapter.INSTANCE != null) {
            IceAdapter.close(0);
        }
    }

    @Test
    public void starter() throws InterruptedException {
        System.setProperty("java.awt.headless", "false");
        String[] args = {
                "--id=12345",
                "--game-id=67890",
                "--login=testUser",
                "--gpgnet-port=5000",
                "--rpc-port=5001",
                "--lobby-port=5002",
                "--debug-window=true",
                "--info-window=true"
        };

        // When: Запуск через main
        IceAdapter.main(args);

        // Then: Проверка, что экземпляр создан
        assertNotNull(IceAdapter.INSTANCE, "INSTANCE должен быть инициализирован");
        adapter = IceAdapter.INSTANCE;
        adapter.onJoinGame("Strogo", 123);
        Thread.sleep(1000000000);
    }

    @Test
    @DisplayName("Должен корректно инициализироваться и запускаться с аргументами")
    public void testStartWithArgs() throws Exception {
        // Given: Поддельные аргументы CLI
        String[] args = {
                "--id=12345",
                "--game-id=67890",
                "--login=testUser",
                "--gpgnet-port=5000",
                "--rpc-port=5001",
                "--lobby-port=5002"
        };

        // When: Запуск через main
        IceAdapter.main(args);

        // Then: Проверка, что экземпляр создан
        assertNotNull(IceAdapter.INSTANCE, "INSTANCE должен быть инициализирован");
        adapter = IceAdapter.INSTANCE;

        // Проверка, что параметры установлены
        assertEquals(12345, IceAdapter.getId());
        assertEquals(67890, IceAdapter.getGameId());
        assertEquals("testUser", IceAdapter.getLogin());

        // Проверка, что серверы инициализированы
        assertNotNull(adapter.getGpgNetServer(), "GPGNetServer должен быть создан");
        assertNotNull(adapter.getRpcService(), "RPCService должен быть создан");

        // Проверка, что версия установлена
        assertNotNull(IceAdapter.getVersion());
    }

    @Test
    @DisplayName("Должен корректно завершаться без ошибок")
    public void testCloseGracefully() {
        // Given: Запустим с минимальными параметрами
        IceAdapter.main(new String[]{
                "--id=1", "--game-id=1", "--login=test", "--gpgnet-port=0", "--rpc-port=0", "--lobby-port=0"
        });

        assertNotNull(IceAdapter.INSTANCE);

        // When: Закрываем
        IceAdapter.close(0);

        // Then: Проверяем, что закрытие прошло
        assertTrue(true, "Закрытие должно завершиться без исключений");
    }

    @Test
    @DisplayName("Вызов call() должен вернуть 0 и установить INSTANCE")
    public void testCallReturnsZero() throws Exception {
        // Given
        IceAdapter adapter = new IceAdapter();
        String[] args = {"--id=1", "--game-id=1", "--login=test", "--gpgnet-port=0", "--rpc-port=0", "--lobby-port=0"};
        new CommandLine(adapter).setUnmatchedArgumentsAllowed(true).parseArgs(args);

        // When
        Integer result = adapter.call();

        // Then
        assertEquals(0, result);
        assertNotNull(IceAdapter.INSTANCE);
    }
}