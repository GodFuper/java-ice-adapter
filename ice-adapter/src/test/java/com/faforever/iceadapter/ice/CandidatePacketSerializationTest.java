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
        assertEquals(CandidateType.SERVER_REFLEXIVE_CANDIDATE, packet.type());
        assertEquals(1, packet.generation());
    }

    @Test
    @DisplayName("Should deserialize CandidatePacket with adapter field")
    void shouldDeserializeWithAdapterField() throws Exception {
        String json =
                """
                {
                    "candidate": "candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host",
                    "sdpMid": "0",
                    "sdpMLineIndex": 0,
                    "adapter": "faf-ice-adapter"
                }
                """;

        CandidatePacket packet = objectMapper.readValue(json, CandidatePacket.class);
        assertNotNull(packet);
        assertEquals(CandidatePacket.ADAPTER_FAF_ICE_ADAPTER, packet.adapter());
    }

    @Test
    @DisplayName("Should deserialize CandidatePacket without adapter field (from faf-pioneer)")
    void shouldDeserializeWithoutAdapterFieldFromPioneer() throws Exception {
        String pioneerJson =
                """
                {
                    "candidate": "candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host",
                    "sdpMid": "0",
                    "sdpMLineIndex": 0
                }
                """;

        CandidatePacket packet = objectMapper.readValue(pioneerJson, CandidatePacket.class);
        assertNotNull(packet);
        assertNull(packet.adapter(), "adapter should be null when received from faf-pioneer or legacy client");
    }

    @Test
    @DisplayName("Should deserialize CandidatePacket from Pion ICE candidate format with address and numeric protocol")
    void shouldDeserializeFromPionCandidateFields() throws Exception {
        String pionJson =
                """
                {
                    "foundation": "3745247045",
                    "priority": 2130706431,
                    "address": "fdfd::1a63:324c",
                    "protocol": 1.0,
                    "port": 62776,
                    "type": "host",
                    "component": 1,
                    "relatedAddress": "",
                    "relatedPort": 0,
                    "sdpMid": "0",
                    "sdpMLineIndex": 0
                }
                """;

        CandidatePacket packet = objectMapper.readValue(pionJson, CandidatePacket.class);
        assertNotNull(packet);
        assertEquals("3745247045", packet.foundation());
        assertEquals("udp", packet.protocol());
        assertEquals(2130706431L, packet.priority());
        assertEquals("fdfd::1a63:324c", packet.ip());
        assertEquals(62776, packet.port());
        assertEquals(CandidateType.HOST_CANDIDATE, packet.type());
    }
}
