package com.faforever.iceadapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    public void uiStarter() throws InterruptedException {
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

        IceAdapter.main(args);

        assertNotNull(IceAdapter.INSTANCE);
        adapter = IceAdapter.INSTANCE;
        adapter.onJoinGame("Player2", 123);
        Thread.sleep(1000000000);
    }
}