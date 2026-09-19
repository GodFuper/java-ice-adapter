package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.util.CandidateUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

public class CandidatePacketDeserializer extends JsonDeserializer<CandidatePacket> {

    @Override
    public CandidatePacket deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull()) {
            return null;
        }

        // Standard WebRTC candidate string from Go faf-pioneer or WebRTC peers
        if (node.hasNonNull("candidate")) {
            String candidateStr = node.get("candidate").asText();
            CandidatePacket parsed = CandidateUtil.webRtcCandidateToPacket(candidateStr);
            if (parsed != null) {
                return parsed;
            }
        }

        // Fallback: parse from legacy fields
        String foundation = node.has("foundation") ? node.get("foundation").asText() : "";
        String protocol = node.has("protocol") ? node.get("protocol").asText() : "udp";
        long priority = node.has("priority") ? node.get("priority").asLong() : 0L;
        String ip = node.has("ip") ? node.get("ip").asText() : "";
        int port = node.has("port") ? node.get("port").asInt() : 0;

        CandidateType type = CandidateType.HOST_CANDIDATE;
        if (node.hasNonNull("type")) {
            try {
                type = CandidateType.valueOf(node.get("type").asText());
            } catch (Exception ignored) {
            }
        }

        int generation = node.has("generation") ? node.get("generation").asInt() : 0;
        String id = node.has("id") ? node.get("id").asText() : "0";
        String relAddr = node.has("relAddr") && !node.get("relAddr").isNull()
                ? node.get("relAddr").asText()
                : null;
        int relPort = node.has("relPort") ? node.get("relPort").asInt() : 0;

        return new CandidatePacket(
                foundation, protocol, priority, ip, port, type, generation, id, relAddr, relPort);
    }
}
