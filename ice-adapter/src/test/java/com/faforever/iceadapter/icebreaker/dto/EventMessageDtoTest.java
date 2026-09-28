package com.faforever.iceadapter.icebreaker.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventMessageDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void connectedMessage_serializationAndDeserialization() throws Exception {
        EventMessageDto message = new EventMessageDto.Connected(100L, 1L, 2L);

        String json = objectMapper.writeValueAsString(message);
        JsonNode tree = objectMapper.readTree(json);

        assertEquals("connected", tree.get("eventType").asText());
        assertEquals(100L, tree.get("gameId").asLong());
        assertEquals(1L, tree.get("senderId").asLong());
        assertEquals(2L, tree.get("recipientId").asLong());

        EventMessageDto deserialized = objectMapper.readValue(json, EventMessageDto.class);
        assertInstanceOf(EventMessageDto.Connected.class, deserialized);
        EventMessageDto.Connected connected = (EventMessageDto.Connected) deserialized;
        assertEquals(100L, connected.gameId());
        assertEquals(1L, connected.senderId());
        assertEquals(2L, connected.recipientId());
    }

    @Test
    void connectedMessage_nullableRecipientId() throws Exception {
        EventMessageDto message = new EventMessageDto.Connected(100L, 1L, null);

        String json = objectMapper.writeValueAsString(message);
        EventMessageDto deserialized = objectMapper.readValue(json, EventMessageDto.class);

        assertInstanceOf(EventMessageDto.Connected.class, deserialized);
        EventMessageDto.Connected connected = (EventMessageDto.Connected) deserialized;
        assertNull(connected.recipientId());
    }

    @Test
    void candidatesMessage_serializationAndDeserialization() throws Exception {
        JsonNode session = objectMapper.readTree("{\"type\":\"offer\",\"sdp\":\"v=0...\"}");
        JsonNode candidates = objectMapper.readTree("[{\"foundation\":\"1\",\"protocol\":\"udp\"}]");
        EventMessageDto message = new EventMessageDto.Candidates(100L, 1L, 2L, session, candidates);

        String json = objectMapper.writeValueAsString(message);
        JsonNode tree = objectMapper.readTree(json);

        assertEquals("candidates", tree.get("eventType").asText());
        assertEquals(100L, tree.get("gameId").asLong());
        assertEquals(1L, tree.get("senderId").asLong());
        assertEquals(2L, tree.get("recipientId").asLong());
        assertEquals("offer", tree.get("session").get("type").asText());
        assertTrue(tree.get("candidates").isArray());
        assertEquals(1, tree.get("candidates").size());

        EventMessageDto deserialized = objectMapper.readValue(json, EventMessageDto.class);
        assertInstanceOf(EventMessageDto.Candidates.class, deserialized);
        EventMessageDto.Candidates candidatesMsg = (EventMessageDto.Candidates) deserialized;
        assertEquals(100L, candidatesMsg.gameId());
        assertEquals(1L, candidatesMsg.senderId());
        assertEquals(2L, candidatesMsg.recipientId());
        assertEquals("offer", candidatesMsg.session().get("type").asText());
        assertEquals("1", candidatesMsg.candidates().get(0).get("foundation").asText());
    }

    @Test
    void peerClosingMessage_serializationAndDeserialization() throws Exception {
        EventMessageDto message = new EventMessageDto.PeerClosing(100L, 1L, 2L);

        String json = objectMapper.writeValueAsString(message);
        JsonNode tree = objectMapper.readTree(json);

        assertEquals("peerClosing", tree.get("eventType").asText());
        assertEquals(100L, tree.get("gameId").asLong());
        assertEquals(1L, tree.get("senderId").asLong());
        assertEquals(2L, tree.get("recipientId").asLong());

        EventMessageDto deserialized = objectMapper.readValue(json, EventMessageDto.class);
        assertInstanceOf(EventMessageDto.PeerClosing.class, deserialized);
        EventMessageDto.PeerClosing peerClosing = (EventMessageDto.PeerClosing) deserialized;
        assertEquals(100L, peerClosing.gameId());
        assertEquals(1L, peerClosing.senderId());
        assertEquals(2L, peerClosing.recipientId());
    }

    @Test
    void sessionTokenDtos_serializationAndDeserialization() throws Exception {
        SessionTokenRequest request = new SessionTokenRequest(100L);
        String reqJson = objectMapper.writeValueAsString(request);
        SessionTokenRequest deserializedReq = objectMapper.readValue(reqJson, SessionTokenRequest.class);
        assertEquals(100L, deserializedReq.gameId());

        SessionTokenResponse response = new SessionTokenResponse("jwt.token.val");
        String resJson = objectMapper.writeValueAsString(response);
        SessionTokenResponse deserializedRes = objectMapper.readValue(resJson, SessionTokenResponse.class);
        assertEquals("jwt.token.val", deserializedRes.jwt());
    }

    @Test
    void sessionGameResponse_serializationAndDeserialization() throws Exception {
        IceServerDto server = new IceServerDto("turn-1", "user", "pass", List.of("turn:coturn.faforever.com:3478"));
        SessionGameResponse gameResponse = new SessionGameResponse("game-1", true, List.of(server));

        String json = objectMapper.writeValueAsString(gameResponse);
        SessionGameResponse deserialized = objectMapper.readValue(json, SessionGameResponse.class);

        assertEquals("game-1", deserialized.id());
        assertTrue(deserialized.forceRelay());
        assertNotNull(deserialized.servers());
        assertEquals(1, deserialized.servers().size());
        assertEquals("turn-1", deserialized.servers().get(0).id());
        assertEquals("user", deserialized.servers().get(0).username());
        assertEquals("pass", deserialized.servers().get(0).credential());
        assertEquals(
                List.of("turn:coturn.faforever.com:3478"),
                deserialized.servers().get(0).urls());
    }
}
