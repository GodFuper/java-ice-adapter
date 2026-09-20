package client.pioneer;

import static org.junit.jupiter.api.Assertions.*;

import client.forgedalliance.FaDataInputStream;
import client.forgedalliance.FaDataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("Pioneer Client & Launcher Integration Tests")
class PioneerClientIntegrationTest {

    private PioneerGpgNetLauncherServer server;
    private PioneerAdapter adapter;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (adapter != null) {
            adapter.stop();
            adapter = null;
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("PioneerGpgNetLauncherServer accepts connection and exchanges GPGNet binary messages")
    void testGpgNetLauncherServerMessaging() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }

        server = new PioneerGpgNetLauncherServer(freePort);

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch messageReceivedLatch = new CountDownLatch(1);
        List<String> receivedCommands = new ArrayList<>();

        server.addConnectionListener(connected -> {
            if (connected) {
                connectedLatch.countDown();
            }
        });

        server.addMessageListener((cmd, chunks) -> {
            receivedCommands.add(cmd);
            if ("GameState".equalsIgnoreCase(cmd)) {
                messageReceivedLatch.countDown();
            }
        });

        server.start();

        // Simulate adapter connecting to launcher
        try (Socket clientSocket = new Socket("127.0.0.1", freePort);
                FaDataOutputStream clientOut = new FaDataOutputStream(clientSocket.getOutputStream());
                FaDataInputStream clientIn = new FaDataInputStream(clientSocket.getInputStream())) {

            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Server must accept connection");
            assertTrue(server.isConnected(), "Server must report connected");

            // Client (simulating adapter) sends GameState Idle
            clientOut.writeMessage("GameState", "Idle");
            clientOut.flush();

            assertTrue(messageReceivedLatch.await(5, TimeUnit.SECONDS), "Server must receive GameState message");
            assertEquals("Idle", server.getGameState());

            // Server sends CreateLobby to adapter
            server.createLobby(0, 50000, "LocalPlayer", 1, 1);

            // Read message on client side
            String receivedCmd = clientIn.readString();
            List<Object> receivedChunks = clientIn.readChunks();

            assertEquals("CreateLobby", receivedCmd);
            assertEquals(5, receivedChunks.size());
            assertEquals(0, receivedChunks.get(0));
            assertEquals(50000, receivedChunks.get(1));
            assertEquals("LocalPlayer", receivedChunks.get(2));
            assertEquals(1, receivedChunks.get(3));
            assertEquals(1, receivedChunks.get(4));

            // Server sends HostGame
            server.hostGame("SCMP_001");
            assertEquals("HostGame", clientIn.readString());
            List<Object> hostChunks = clientIn.readChunks();
            assertEquals(1, hostChunks.size());
            assertEquals("SCMP_001", hostChunks.get(0));

            // Server sends ConnectToPeer
            server.connectToPeer("127.0.0.1:18000", "RemotePlayer", 2);
            assertEquals("ConnectToPeer", clientIn.readString());
            List<Object> connectChunks = clientIn.readChunks();
            assertEquals(3, connectChunks.size());
            assertEquals("127.0.0.1:18000", connectChunks.get(0));
            assertEquals("RemotePlayer", connectChunks.get(1));
            assertEquals(2, connectChunks.get(2));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("PioneerGpgNetLauncherServer buffers and flushes messages sent before connection")
    void testGpgNetLauncherServerQueueing() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }

        server = new PioneerGpgNetLauncherServer(freePort);
        server.start();

        // Send messages BEFORE any client connects
        server.createLobby(0, 50000, "LocalPlayer", 1, 1);
        server.hostGame("SCMP_001");
        server.connectToPeer("127.0.0.1:18000", "RemotePlayer", 2);

        // Now connect client
        try (Socket clientSocket = new Socket("127.0.0.1", freePort);
                FaDataOutputStream clientOut = new FaDataOutputStream(clientSocket.getOutputStream());
                FaDataInputStream clientIn = new FaDataInputStream(clientSocket.getInputStream())) {

            // All 3 queued messages must be delivered in order
            assertEquals("CreateLobby", clientIn.readString());
            assertEquals(5, clientIn.readChunks().size());

            assertEquals("HostGame", clientIn.readString());
            assertEquals(1, clientIn.readChunks().size());

            assertEquals("ConnectToPeer", clientIn.readString());
            assertEquals(3, clientIn.readChunks().size());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("PioneerAdapter initializes and allocates isolated ports")
    void testPioneerAdapterPortAllocation() {
        adapter = new PioneerAdapter(1, "UserA", 12345, "token", "http://localhost:8080");

        assertEquals(1, adapter.getUserId());
        assertEquals("UserA", adapter.getUserName());
        assertEquals(12345, adapter.getGameId());
        assertEquals("Stopped", adapter.getStatusText().get());
        assertFalse(adapter.getConnected().get());
    }
}
