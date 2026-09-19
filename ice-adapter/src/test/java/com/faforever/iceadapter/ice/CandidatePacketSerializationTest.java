package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class CandidatePacketSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should serialize CandidatePacket with both WebRTC standard candidate string and legacy fields")
    void shouldSerializeToWebRtcAndLegacyFields() throws Exception {
        CandidatePacket packet = new CandidatePacket(
                "423499824", "udp", 2113937151L, "192.168.1.100", 50002, CandidateType.HOST_CANDIDATE, 0, "1", null, 0);

        String json = objectMapper.writeValueAsString(packet);
        JsonNode node = objectMapper.readTree(json);

        // WebRTC fields
        assertTrue(node.hasNonNull("candidate"));
        String candidateStr = node.get("candidate").asText();
        assertTrue(candidateStr.startsWith("candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host"));
        assertEquals("0", node.get("sdpMid").asText());
        assertEquals(0, node.get("sdpMLineIndex").asInt());

        // Legacy fields
        assertEquals("192.168.1.100", node.get("ip").asText());
        assertEquals(50002, node.get("port").asInt());
        assertEquals("HOST_CANDIDATE", node.get("type").asText());
    }

    @Test
    @DisplayName("Should deserialize from Go Pion / WebRTC candidate format")
    void shouldDeserializeFromGoPionFormat() throws Exception {
        String goPionJson =
                """
                {
                    "candidate": "candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host",
                    "sdpMid": "0",
                    "sdpMLineIndex": 0
                }
                """;

        CandidatePacket packet = objectMapper.readValue(goPionJson, CandidatePacket.class);
        assertNotNull(packet);
        assertEquals("423499824", packet.foundation());
        assertEquals("udp", packet.protocol());
        assertEquals(2113937151L, packet.priority());
        assertEquals("192.168.1.100", packet.ip());
        assertEquals(50002, packet.port());
        assertEquals(CandidateType.HOST_CANDIDATE, packet.type());
    }

    @Test
    @DisplayName("Should deserialize from legacy Java FAF candidate format")
    void shouldDeserializeFromLegacyFormat() throws Exception {
        String legacyJson =
                """
                {
                    "foundation": "423499824",
                    "protocol": "udp",
                    "priority": 2113937151,
                    "ip": "10.0.0.5",
                    "port": 6112,
                    "type": "SERVER_REFLEXIVE_CANDIDATE",
                    "generation": 1,
                    "id": "cand_0"
                }
                """;

        CandidatePacket packet = objectMapper.readValue(legacyJson, CandidatePacket.class);
        assertNotNull(packet);
        assertEquals("423499824", packet.foundation());
        assertEquals("10.0.0.5", packet.ip());
        assertEquals(6112, packet.port());
        assertEquals(CandidateType.SERVER_REFLEXIVE_CANDIDATE, packet.type());
        assertEquals(1, packet.generation());
    }
}
