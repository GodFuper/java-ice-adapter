package com.faforever.iceadapter.icebreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcebreakerMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toIcebreakerCandidates_offerMessage() {
        List<CandidatePacket> packets = List.of(
                new CandidatePacket(
                        "1", "UDP", 2122260223L, "192.168.1.10", 50000, CandidateType.HOST_CANDIDATE, 0, "0", null, 0),
                new CandidatePacket(
                        "2",
                        "UDP",
                        1686052863L,
                        "1.2.3.4",
                        50001,
                        CandidateType.SERVER_REFLEXIVE_CANDIDATE,
                        0,
                        "0",
                        null,
                        0),
                new CandidatePacket(
                        "3", "TCP", 33562367L, "5.6.7.8", 50002, CandidateType.RELAYED_CANDIDATE, 0, "0", null, 0),
                new CandidatePacket(
                        "4",
                        "udp",
                        1853824767L,
                        "192.168.1.20",
                        50003,
                        CandidateType.PEER_REFLEXIVE_CANDIDATE,
                        0,
                        "0",
                        null,
                        0));
        CandidatesMessage message = new CandidatesMessage(1, 2, "v=0\r\nsdp-offer", "offer", packets);

        EventMessageDto.Candidates result = IcebreakerMessageConverter.toIcebreakerCandidates(100L, message);

        assertEquals(100L, result.gameId());
        assertEquals(1L, result.senderId());
        assertEquals(2L, result.recipientId());

        JsonNode session = result.session();
        assertNotNull(session);
        assertEquals("offer", session.get("type").asText());
        assertEquals("v=0\r\nsdp-offer", session.get("sdp").asText());

        JsonNode candidates = result.candidates();
        assertNotNull(candidates);
        assertTrue(candidates.isArray());
        assertEquals(4, candidates.size());

        JsonNode host = candidates.get(0);
        assertEquals("1", host.get("foundation").asText());
        assertEquals(2122260223L, host.get("priority").asLong());
        assertEquals("192.168.1.10", host.get("address").asText());
        assertEquals("udp", host.get("protocol").asText());
        assertEquals(50000, host.get("port").asInt());
        assertEquals("host", host.get("type").asText());
        assertEquals(1, host.get("component").asInt());

        assertEquals("srflx", candidates.get(1).get("type").asText());
        assertEquals("relay", candidates.get(2).get("type").asText());
        assertEquals("tcp", candidates.get(2).get("protocol").asText());
        assertEquals("prflx", candidates.get(3).get("type").asText());
    }

    @Test
    void toIcebreakerCandidates_answerMessage() {
        CandidatesMessage message = new CandidatesMessage(2, 1, "v=0\r\nsdp-answer", "answer", List.of());

        EventMessageDto.Candidates result = IcebreakerMessageConverter.toIcebreakerCandidates(100L, message);

        assertEquals(100L, result.gameId());
        assertEquals(2L, result.senderId());
        assertEquals(1L, result.recipientId());

        JsonNode session = result.session();
        assertNotNull(session);
        assertEquals("answer", session.get("type").asText());
        assertEquals("v=0\r\nsdp-answer", session.get("sdp").asText());

        JsonNode candidates = result.candidates();
        assertNotNull(candidates);
        assertTrue(candidates.isArray());
        assertEquals(0, candidates.size());
    }

    @Test
    void fromIcebreakerCandidates_toCandidatesMessage() {
        ObjectNode session = objectMapper.createObjectNode();
        session.put("type", "answer");
        session.put("sdp", "v=0\r\nsdp-data");

        ArrayNode candidates = objectMapper.createArrayNode();
        candidates.add(objectMapper
                .createObjectNode()
                .put("foundation", "f1")
                .put("priority", 2122260223L)
                .put("address", "192.168.1.10")
                .put("protocol", "udp")
                .put("port", 50000)
                .put("type", "host")
                .put("component", 1));
        candidates.add(objectMapper
                .createObjectNode()
                .put("foundation", "f2")
                .put("priority", 1686052863L)
                .put("address", "1.2.3.4")
                .put("protocol", "udp")
                .put("port", 50001)
                .put("type", "srflx")
                .put("component", 1));
        candidates.add(objectMapper
                .createObjectNode()
                .put("foundation", "f3")
                .put("priority", 33562367L)
                .put("address", "5.6.7.8")
                .put("protocol", "tcp")
                .put("port", 50002)
                .put("type", "relay")
                .put("component", 1));
        candidates.add(objectMapper
                .createObjectNode()
                .put("foundation", "f4")
                .put("priority", 1853824767L)
                .put("address", "192.168.1.20")
                .put("protocol", "udp")
                .put("port", 50003)
                .put("type", "prflx")
                .put("component", 1));
        candidates.add(objectMapper
                .createObjectNode()
                .put("foundation", "f5")
                .put("priority", 100L)
                .put("address", "192.168.1.30")
                .put("protocol", "udp")
                .put("port", 50004)
                .put("type", "unknown_type")
                .put("component", 1));

        EventMessageDto.Candidates dto = new EventMessageDto.Candidates(100L, 10L, 20L, session, candidates);

        CandidatesMessage message = IcebreakerMessageConverter.fromIcebreakerCandidates(dto);

        assertEquals(10, message.srcId());
        assertEquals(20, message.destId());
        assertEquals("answer", message.ufrag());
        assertEquals("v=0\r\nsdp-data", message.password());
        assertEquals(5, message.candidates().size());

        CandidatePacket p1 = message.candidates().get(0);
        assertEquals("f1", p1.foundation());
        assertEquals(2122260223L, p1.priority());
        assertEquals("192.168.1.10", p1.ip());
        assertEquals("udp", p1.protocol());
        assertEquals(50000, p1.port());
        assertEquals(CandidateType.HOST_CANDIDATE, p1.type());

        assertEquals(
                CandidateType.SERVER_REFLEXIVE_CANDIDATE,
                message.candidates().get(1).type());
        assertEquals(
                CandidateType.RELAYED_CANDIDATE, message.candidates().get(2).type());
        assertEquals(
                CandidateType.PEER_REFLEXIVE_CANDIDATE,
                message.candidates().get(3).type());
        assertEquals(CandidateType.HOST_CANDIDATE, message.candidates().get(4).type());
    }

    @Test
    void fromIcebreakerCandidates_nullableFields() {
        EventMessageDto.Candidates dto = new EventMessageDto.Candidates(100L, 10L, null, null, null);

        CandidatesMessage message = IcebreakerMessageConverter.fromIcebreakerCandidates(dto);

        assertEquals(10, message.srcId());
        assertEquals(0, message.destId());
        assertEquals("offer", message.ufrag());
        assertEquals("", message.password());
        assertTrue(message.candidates().isEmpty());
    }

    @Test
    void roundTrip_preservesFields() {
        List<CandidatePacket> packets = List.of(
                new CandidatePacket(
                        "10", "udp", 2122260223L, "10.0.0.1", 30000, CandidateType.HOST_CANDIDATE, 0, "0", null, 0),
                new CandidatePacket(
                        "20",
                        "udp",
                        1686052863L,
                        "20.0.0.1",
                        30001,
                        CandidateType.SERVER_REFLEXIVE_CANDIDATE,
                        0,
                        "0",
                        null,
                        0),
                new CandidatePacket(
                        "30", "tcp", 33562367L, "30.0.0.1", 30002, CandidateType.RELAYED_CANDIDATE, 0, "0", null, 0),
                new CandidatePacket(
                        "40",
                        "udp",
                        1853824767L,
                        "40.0.0.1",
                        30003,
                        CandidateType.PEER_REFLEXIVE_CANDIDATE,
                        0,
                        "0",
                        null,
                        0));
        CandidatesMessage original = new CandidatesMessage(5, 6, "v=0\r\nsdp-roundtrip", "offer", packets);

        EventMessageDto.Candidates dto = IcebreakerMessageConverter.toIcebreakerCandidates(42L, original);
        CandidatesMessage convertedBack = IcebreakerMessageConverter.fromIcebreakerCandidates(dto);

        assertEquals(original.srcId(), convertedBack.srcId());
        assertEquals(original.destId(), convertedBack.destId());
        assertEquals(original.ufrag(), convertedBack.ufrag());
        assertEquals(original.password(), convertedBack.password());
        assertEquals(original.candidates().size(), convertedBack.candidates().size());

        for (int i = 0; i < original.candidates().size(); i++) {
            CandidatePacket origPacket = original.candidates().get(i);
            CandidatePacket backPacket = convertedBack.candidates().get(i);

            assertEquals(origPacket.foundation(), backPacket.foundation());
            assertEquals(origPacket.priority(), backPacket.priority());
            assertEquals(origPacket.ip(), backPacket.ip());
            assertEquals(
                    origPacket.protocol().toLowerCase(), backPacket.protocol().toLowerCase());
            assertEquals(origPacket.port(), backPacket.port());
            assertEquals(origPacket.type(), backPacket.type());
        }
    }
}
