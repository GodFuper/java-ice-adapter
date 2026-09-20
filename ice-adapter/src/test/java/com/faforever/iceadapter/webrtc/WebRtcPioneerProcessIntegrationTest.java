package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.InMemoryDatagramSocket;
import com.faforever.iceadapter.ice.base.InMemoryRpcBus;
import com.faforever.iceadapter.ice.base.TestGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.ObjectMapperUtil;
import dev.onvoid.webrtc.RTCDataChannelState;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Slf4j
@Tag("integration")
@DisplayName("WebRTC Pioneer (Go Pion) Live Process Integration Tests")
class WebRtcPioneerProcessIntegrationTest {

    private static final String FAF_PIONEER_DIR = "temp/faf-pioneer";

    public record CandidateDto(String candidate, String sdpMid, int sdpMLineIndex) {}

    public record SdpMessageDto(String type, String sdp, List<CandidateDto> candidates) {}

    private static File pioneerHarnessExe;
    private WebRtcSession localSession;
    private TestGameSession gameSession;
    private InMemoryDatagramSocket faSocket;
    private InMemoryRpcBus rpcBus;
    private Process pioneerProcess;

    @BeforeAll
    static void findPioneerHarness() {
        List<File> candidates = List.of(
                new File("../" + FAF_PIONEER_DIR + "/direct-harness.exe"),
                new File(FAF_PIONEER_DIR + "/direct-harness.exe"),
                new File(FAF_PIONEER_DIR + "/direct-harness"),
                new File("../" + FAF_PIONEER_DIR + "/direct-harness"));

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                pioneerHarnessExe = candidate;
                log.info("Found pioneer test harness at: {}", candidate.getAbsolutePath());
                break;
            }
        }

        if (pioneerHarnessExe == null) {
            // Attempt to build if go is present
            File pioneerDir = new File("../" + FAF_PIONEER_DIR);
            if (!pioneerDir.exists()) {
                pioneerDir = new File(FAF_PIONEER_DIR);
            }
            if (pioneerDir.exists()) {
                try {
                    log.info("Attempting to build direct-harness in {}", pioneerDir.getAbsolutePath());
                    ProcessBuilder pb = new ProcessBuilder("go", "build", "-o", "direct-harness.exe", "./cmd/direct-harness");
                    pb.directory(pioneerDir);
                    Process p = pb.start();
                    boolean finished = p.waitFor(30, TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0) {
                        File built = new File(pioneerDir, "direct-harness.exe");
                        if (built.exists()) {
                            pioneerHarnessExe = built;
                            log.info("Successfully built pioneer test harness: {}", built.getAbsolutePath());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not compile direct-harness via go", e);
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (localSession != null) {
            localSession.close();
            localSession = null;
        }
        if (gameSession != null) {
            gameSession.close();
            gameSession = null;
        }
        if (faSocket != null) {
            faSocket.close();
            faSocket = null;
        }
        if (rpcBus != null) {
            rpcBus.stop();
            rpcBus = null;
        }
        if (pioneerProcess != null) {
            pioneerProcess.destroyForcibly();
            try {
                pioneerProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            pioneerProcess = null;
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Java (Offerer) connects to real Go Pioneer (Answerer) and exchanges game packets")
    void testJavaOffererToPioneerAnswerer() throws Exception {
        Assumptions.assumeTrue(pioneerHarnessExe != null, "Pioneer direct-harness executable must be available");

        ProcessBuilder pb = new ProcessBuilder(pioneerHarnessExe.getAbsolutePath(), "-role=answerer");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pioneerProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(pioneerProcess.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(pioneerProcess.getOutputStream(), StandardCharsets.UTF_8));

        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        localSession = new WebRtcSession(factory);

        List<byte[]> receivedPackets = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);

        localSession.init(
                true,
                List.of(),
                (channelLabel, data, isBinary) -> {
                    log.debug("Java received message on channel {}: len={}", channelLabel, data.length);
                    receivedPackets.add(data);
                },
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {
                        log.info("Java WebRtcSession CONNECTED with Pioneer");
                        connectedLatch.countDown();
                    }

                    @Override
                    public void onDisconnected() {
                        log.info("Java WebRtcSession DISCONNECTED");
                    }

                    @Override
                    public void onError(String error) {
                        fail("Local session error: " + error);
                    }

                    @Override
                    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                        log.info("Java offer created with {} candidates", candidates.size());
                        List<CandidateDto> candidateDtos = new ArrayList<>();
                        for (CandidatePacket cp : candidates) {
                            String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                            if (candStr != null) {
                                candidateDtos.add(new CandidateDto(candStr, "0", 0));
                            }
                        }
                        SdpMessageDto offerDto = new SdpMessageDto("offer", sdp, candidateDtos);
                        String offerJson = ObjectMapperUtil.toJson(offerDto);

                        try {
                            writer.write(offerJson);
                            writer.newLine();
                            writer.flush();
                        } catch (IOException e) {
                            fail("Failed to write offer to Pioneer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onAnswerCreated(String sdp) {}

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {}
                });

        localSession.createOffer();

        // Read answer from Pioneer
        String answerLine = reader.readLine();
        assertNotNull(answerLine, "Pioneer must output answer JSON");
        log.info("Received answer from Pioneer: length={}", answerLine.length());

        SdpMessageDto answerDto = ObjectMapperUtil.fromJson(answerLine, SdpMessageDto.class);
        assertNotNull(answerDto, "Answer DTO must not be null");
        assertEquals("answer", answerDto.type());

        List<CandidatePacket> pioneerCandidatePackets = new ArrayList<>();
        if (answerDto.candidates() != null) {
            for (CandidateDto cd : answerDto.candidates()) {
                CandidatePacket parsed = CandidateUtil.webRtcCandidateToPacket(cd.candidate());
                if (parsed != null) {
                    pioneerCandidatePackets.add(new CandidatePacket(
                            parsed.foundation(),
                            parsed.protocol(),
                            parsed.priority(),
                            parsed.ip(),
                            parsed.port(),
                            parsed.type(),
                            parsed.generation(),
                            parsed.id(),
                            parsed.relAddr(),
                            parsed.relPort(),
                            null)); // Pioneer candidates do not have "adapter" marker
                }
            }
        }

        localSession.processRemoteAnswer(answerDto.sdp(), pioneerCandidatePackets);

        assertTrue(connectedLatch.await(15, TimeUnit.SECONDS), "Session should connect with Pioneer");
        assertTrue(localSession.isConnected(), "localSession must be connected");
        assertFalse(localSession.isRemoteIsJavaAdapter(), "Pioneer must NOT be recognized as Java adapter");
        assertTrue(localSession.getControlDataChannel().isEmpty(), "No controlData channel should be created for Pioneer");
        assertTrue(localSession.getDataChannel().isPresent(), "gameData channel must exist");
        assertEquals(RTCDataChannelState.OPEN, localSession.getDataChannel().get().getState(), "gameData channel must be OPEN");

        // Send 50 packets of varying sizes (from 16 to 1400 bytes)
        int numPackets = 50;
        List<byte[]> sentPackets = new ArrayList<>();
        for (int i = 0; i < numPackets; i++) {
            int payloadSize = 16 + (i * 25);
            byte[] payload = new byte[payloadSize];
            Arrays.fill(payload, (byte) (i & 0xFF));
            payload[0] = (byte) ((i >> 8) & 0xFF);
            payload[1] = (byte) (i & 0xFF);
            sentPackets.add(payload);

            localSession.sendGameDataAsync(payload);
        }

        // Wait for all echoed packets to arrive
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000 && receivedPackets.size() < numPackets) {
            Thread.sleep(10);
        }

        assertEquals(numPackets, receivedPackets.size(), "All sent packets must be echoed back by Pioneer");
        for (int i = 0; i < numPackets; i++) {
            assertArrayEquals(sentPackets.get(i), receivedPackets.get(i), "Packet " + i + " payload must match bit-exact");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Go Pioneer (Offerer) connects to Java (Answerer) and exchanges game packets")
    void testPioneerOffererToJavaAnswerer() throws Exception {
        Assumptions.assumeTrue(pioneerHarnessExe != null, "Pioneer direct-harness executable must be available");

        ProcessBuilder pb = new ProcessBuilder(pioneerHarnessExe.getAbsolutePath(), "-role=offerer", "-channel=gameData");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pioneerProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(pioneerProcess.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(pioneerProcess.getOutputStream(), StandardCharsets.UTF_8));

        // Read offer from Pioneer
        String offerLine = reader.readLine();
        assertNotNull(offerLine, "Pioneer must output offer JSON");
        log.info("Received offer from Pioneer: length={}", offerLine.length());

        SdpMessageDto offerDto = ObjectMapperUtil.fromJson(offerLine, SdpMessageDto.class);
        assertNotNull(offerDto, "Offer DTO must not be null");
        assertEquals("offer", offerDto.type());

        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        localSession = new WebRtcSession(factory);

        List<byte[]> receivedPackets = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);

        localSession.init(
                false,
                List.of(),
                (channelLabel, data, isBinary) -> {
                    log.debug("Java received message on channel {}: len={}", channelLabel, data.length);
                    receivedPackets.add(data);
                },
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {
                        log.info("Java WebRtcSession (Answerer) CONNECTED with Pioneer");
                        connectedLatch.countDown();
                    }

                    @Override
                    public void onDisconnected() {
                        log.info("Java WebRtcSession DISCONNECTED");
                    }

                    @Override
                    public void onError(String error) {
                        fail("Local session error: " + error);
                    }

                    @Override
                    public void onOfferCreated(String sdp) {}

                    @Override
                    public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
                        log.info("Java answer created with {} candidates", candidates.size());
                        List<CandidateDto> candidateDtos = new ArrayList<>();
                        for (CandidatePacket cp : candidates) {
                            String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                            if (candStr != null) {
                                candidateDtos.add(new CandidateDto(candStr, "0", 0));
                            }
                        }
                        SdpMessageDto answerDto = new SdpMessageDto("answer", sdp, candidateDtos);
                        String answerJson = ObjectMapperUtil.toJson(answerDto);

                        try {
                            writer.write(answerJson);
                            writer.newLine();
                            writer.flush();
                        } catch (IOException e) {
                            fail("Failed to write answer to Pioneer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {}
                });

        localSession.processRemoteOffer(offerDto.sdp());
        if (offerDto.candidates() != null) {
            for (CandidateDto cd : offerDto.candidates()) {
                localSession.addRemoteCandidate(cd.sdpMid(), cd.sdpMLineIndex(), cd.candidate());
            }
        }

        assertTrue(connectedLatch.await(15, TimeUnit.SECONDS), "Session should connect with Pioneer");
        assertTrue(localSession.isConnected(), "localSession must be connected");
        assertTrue(localSession.getDataChannel().isPresent(), "gameData channel must exist");
        assertEquals(RTCDataChannelState.OPEN, localSession.getDataChannel().get().getState(), "gameData channel must be OPEN");

        // Send 50 packets
        int numPackets = 50;
        List<byte[]> sentPackets = new ArrayList<>();
        for (int i = 0; i < numPackets; i++) {
            byte[] payload = ("pioneer-test-packet-" + i).getBytes(StandardCharsets.UTF_8);
            sentPackets.add(payload);
            localSession.sendGameDataAsync(payload);
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000 && receivedPackets.size() < numPackets) {
            Thread.sleep(10);
        }

        assertEquals(numPackets, receivedPackets.size(), "All sent packets must be echoed back by Pioneer");
        for (int i = 0; i < numPackets; i++) {
            assertArrayEquals(sentPackets.get(i), receivedPackets.get(i), "Packet " + i + " payload must match");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("High throughput stress test: 200 packets between Java and Pioneer with 0% loss")
    void testThroughputStressTest() throws Exception {
        Assumptions.assumeTrue(pioneerHarnessExe != null, "Pioneer direct-harness executable must be available");

        ProcessBuilder pb = new ProcessBuilder(pioneerHarnessExe.getAbsolutePath(), "-role=answerer");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pioneerProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(pioneerProcess.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(pioneerProcess.getOutputStream(), StandardCharsets.UTF_8));

        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        localSession = new WebRtcSession(factory);

        List<byte[]> receivedPackets = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);

        localSession.init(
                true,
                List.of(),
                (channelLabel, data, isBinary) -> receivedPackets.add(data),
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {
                        connectedLatch.countDown();
                    }

                    @Override
                    public void onDisconnected() {}

                    @Override
                    public void onError(String error) {
                        fail("Local session error: " + error);
                    }

                    @Override
                    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                        List<CandidateDto> candidateDtos = new ArrayList<>();
                        for (CandidatePacket cp : candidates) {
                            String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                            if (candStr != null) {
                                candidateDtos.add(new CandidateDto(candStr, "0", 0));
                            }
                        }
                        try {
                            writer.write(ObjectMapperUtil.toJson(new SdpMessageDto("offer", sdp, candidateDtos)));
                            writer.newLine();
                            writer.flush();
                        } catch (IOException e) {
                            fail("Failed to write offer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onAnswerCreated(String sdp) {}

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {}
                });

        localSession.createOffer();

        String answerLine = reader.readLine();
        assertNotNull(answerLine);
        SdpMessageDto answerDto = ObjectMapperUtil.fromJson(answerLine, SdpMessageDto.class);

        List<CandidatePacket> pioneerCandidates = new ArrayList<>();
        if (answerDto.candidates() != null) {
            for (CandidateDto cd : answerDto.candidates()) {
                CandidatePacket cp = CandidateUtil.webRtcCandidateToPacket(cd.candidate());
                if (cp != null) {
                    pioneerCandidates.add(new CandidatePacket(
                            cp.foundation(), cp.protocol(), cp.priority(), cp.ip(), cp.port(),
                            cp.type(), cp.generation(), cp.id(), cp.relAddr(), cp.relPort(), null));
                }
            }
        }

        localSession.processRemoteAnswer(answerDto.sdp(), pioneerCandidates);

        assertTrue(connectedLatch.await(15, TimeUnit.SECONDS));

        int totalPackets = 200;
        long t0 = System.nanoTime();
        for (int i = 0; i < totalPackets; i++) {
            byte[] msg = ("stress-test-pkt-" + i).getBytes(StandardCharsets.UTF_8);
            localSession.sendGameDataAsync(msg);
        }

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && receivedPackets.size() < totalPackets) {
            Thread.sleep(5);
        }

        long t1 = System.nanoTime();
        double elapsedMs = (t1 - t0) / 1_000_000.0;
        log.info("Sent and received {} packets with Pioneer in {} ms (avg {} ms/pkt)",
                totalPackets, String.format("%.2f", elapsedMs), String.format("%.3f", elapsedMs / totalPackets));

        assertEquals(totalPackets, receivedPackets.size(), "All 200 stress test packets must be received with 0% loss");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("End-to-end full GameSession & simulated FA socket pipeline with live Pioneer process")
    void testFullGameSessionWithLivePioneerProcess() throws Exception {
        Assumptions.assumeTrue(pioneerHarnessExe != null, "Pioneer direct-harness executable must be available");

        ProcessBuilder pb = new ProcessBuilder(pioneerHarnessExe.getAbsolutePath(), "-role=answerer");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pioneerProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(pioneerProcess.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(pioneerProcess.getOutputStream(), StandardCharsets.UTF_8));

        faSocket = new InMemoryDatagramSocket();

        AtomicReference<Peer> peerRef = new AtomicReference<>();

        rpcBus = new InMemoryRpcBus() {
            @Override
            public synchronized void sendToRpc(CandidatesMessage message) {
                if (message.destId() == 2 && message.isOffer()) {
                    List<CandidateDto> candidateDtos = new ArrayList<>();
                    for (CandidatePacket cp : message.candidates()) {
                        String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                        if (candStr != null) {
                            candidateDtos.add(new CandidateDto(candStr, "0", 0));
                        }
                    }
                    SdpMessageDto offerDto = new SdpMessageDto("offer", message.password(), candidateDtos);

                    new Thread(() -> {
                        try {
                            writer.write(ObjectMapperUtil.toJson(offerDto));
                            writer.newLine();
                            writer.flush();

                            String answerLine = reader.readLine();
                            assertNotNull(answerLine, "Pioneer must answer offer");
                            SdpMessageDto answerDto = ObjectMapperUtil.fromJson(answerLine, SdpMessageDto.class);

                            List<CandidatePacket> pioneerCandidates = new ArrayList<>();
                            if (answerDto.candidates() != null) {
                                for (CandidateDto cd : answerDto.candidates()) {
                                    CandidatePacket cp = CandidateUtil.webRtcCandidateToPacket(cd.candidate());
                                    if (cp != null) {
                                        pioneerCandidates.add(new CandidatePacket(
                                                cp.foundation(), cp.protocol(), cp.priority(), cp.ip(), cp.port(),
                                                cp.type(), cp.generation(), cp.id(), cp.relAddr(), cp.relPort(), null));
                                    }
                                }
                            }

                            CandidatesMessage answerMsg = new CandidatesMessage(
                                    2, 1, answerDto.sdp(), "answer", pioneerCandidates);

                            Peer target = peerRef.get();
                            if (target != null) {
                                target.iceMessageFromRPC(answerMsg);
                            }
                        } catch (IOException e) {
                            fail("RPC interaction with Pioneer failed: " + e.getMessage());
                        }
                    }, "Pioneer-RPC-Bridge").start();
                } else {
                    super.sendToRpc(message);
                }
            }
        };

        IceOptions options = new IceOptions(1, 0, "PlayerA", 0, 0, 0, false, false, false, 0, 0, 250.0, null, true, false, true);
        gameSession = new TestGameSession(rpcBus, options, Set.of(PeerModule.CONNECTION_CHECKER_MODULE));
        gameSession.setLobbyPort(faSocket.getLocalPort());

        gameSession.connectToPeer("PioneerPlayer", 2, true, 0, AllowCombination.ALL);
        Peer peer = gameSession.getPeer(2).orElse(null);
        assertNotNull(peer, "Peer must exist");
        peerRef.set(peer);

        rpcBus.registerPeer(1, peer);

        // Connect simulated FA socket to peer's local FA socket
        Thread.sleep(100);
        faSocket.connect(InetAddress.getByName("127.0.0.1"), peer.getFaSocket().getLocalPort());
        faSocket.start();

        // Await connection to Pioneer
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !peer.isConnected()) {
            Thread.sleep(50);
        }

        assertTrue(peer.isConnected(), "Peer connection to live Pioneer process must be connected");

        // Clear initial packets
        faSocket.clear();

        // Send 20 game packets from simulated FA socket to Pioneer
        int numPackets = 20;
        for (int i = 0; i < numPackets; i++) {
            faSocket.sendString("moho-game-data-pkt-" + i);
        }

        // Wait for echoed packets to arrive back at FA socket
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && faSocket.getReceivedBytes().size() < numPackets) {
            Thread.sleep(20);
        }

        List<byte[]> receivedAtFa = faSocket.getReceivedBytes();
        assertEquals(numPackets, receivedAtFa.size(), "FA socket must receive all 20 echoed packets from Pioneer");

        for (int i = 0; i < numPackets; i++) {
            String received = new String(receivedAtFa.get(i), StandardCharsets.UTF_8);
            assertEquals("moho-game-data-pkt-" + i, received, "Game packet payload mismatch");
        }
    }
}
