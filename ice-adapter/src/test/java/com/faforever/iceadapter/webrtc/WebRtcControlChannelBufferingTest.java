package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.CandidatePacket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("WebRTC Control Channel Buffering Tests")
class WebRtcControlChannelBufferingTest {

    private WebRtcSession sessionA;
    private WebRtcSession sessionB;

    @AfterEach
    void tearDown() {
        if (sessionA != null) {
            sessionA.close();
        }
        if (sessionB != null) {
            sessionB.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Control messages sent before controlData channel is OPEN should be buffered and flushed upon opening")
    void testEarlyControlMessageBufferingAndDelivery() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        sessionA = new WebRtcSession(factory);
        sessionB = new WebRtcSession(factory);

        List<byte[]> receivedControlData = new CopyOnWriteArrayList<>();
        CountDownLatch controlReceivedLatch = new CountDownLatch(1);

        CountDownLatch connectedA = new CountDownLatch(1);
        CountDownLatch connectedB = new CountDownLatch(1);

        WebRtcSession.SessionStateHandler handlerA = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                connectedA.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Session A error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                // Remote (B) sets remote Java offer with faf-ice-adapter marker
                sessionB.processRemoteOffer(sdp, candidates);
            }

            @Override
            public void onAnswerCreated(String sdp) {}

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                sessionB.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        WebRtcSession.SessionStateHandler handlerB = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                connectedB.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Session B error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {}

            @Override
            public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
                // Remote (A) receives answer with faf-ice-adapter marker
                sessionA.processRemoteAnswer(sdp, candidates);
            }

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                sessionA.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        sessionA.init(true, List.of(), (channelLabel, data, isBinary) -> {}, handlerA);
        sessionB.init(
                false,
                List.of(),
                (channelLabel, data, isBinary) -> {
                    if (WebRtcSession.CHANNEL_CONTROL_DATA.equals(channelLabel)) {
                        receivedControlData.add(data);
                        controlReceivedLatch.countDown();
                    }
                },
                handlerB);

        // Start offer creation
        sessionA.createOffer();

        assertTrue(connectedA.await(10, TimeUnit.SECONDS), "Session A must connect");
        assertTrue(connectedB.await(10, TimeUnit.SECONDS), "Session B must connect");

        // Send early control message immediately (simulating InfoStatusModule / early status command)
        byte[] earlyCommand = "{\"action\":\"info_relay_status\",\"allow\":true}".getBytes(StandardCharsets.UTF_8);
        boolean sendAccepted = sessionA.sendControlDataAsync(earlyCommand);
        assertTrue(sendAccepted, "sendControlDataAsync must accept/queue message for Java adapter");

        // Wait for control message to be flushed and received by session B
        assertTrue(
                controlReceivedLatch.await(10, TimeUnit.SECONDS),
                "Session B must receive early buffered control message");
        assertEquals(1, receivedControlData.size());
        assertArrayEquals(earlyCommand, receivedControlData.get(0));
    }
}
