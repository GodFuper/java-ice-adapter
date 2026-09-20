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

        String adapter = node.hasNonNull("adapter") ? node.get("adapter").asText() : null;

        // Standard WebRTC candidate string from Go faf-pioneer or WebRTC peers
        if (node.hasNonNull("candidate")) {
            String candidateStr = node.get("candidate").asText();
            CandidatePacket parsed = CandidateUtil.webRtcCandidateToPacket(candidateStr);
            if (parsed != null) {
                return new CandidatePacket(
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
                        adapter);
            }
        }

        // Fallback: parse from individual / legacy fields (including Pion ICE candidate format)
        String foundation = node.hasNonNull("foundation") ? node.get("foundation").asText() : "";
        String protocol = "udp";
        if (node.hasNonNull("protocol")) {
            JsonNode protoNode = node.get("protocol");
            if (protoNode.isNumber()) {
                int protoNum = protoNode.asInt();
                protocol = (protoNum == 2 || protoNum == 4) ? "tcp" : "udp";
            } else {
                String protoText = protoNode.asText().trim().toLowerCase();
                if ("1".equals(protoText) || "1.0".equals(protoText)) {
                    protocol = "udp";
                } else if ("2".equals(protoText) || "2.0".equals(protoText)) {
                    protocol = "tcp";
                } else if (protoText.contains("tcp")) {
                    protocol = "tcp";
                } else {
                    protocol = "udp";
                }
            }
        }

        long priority = node.hasNonNull("priority") ? node.get("priority").asLong() : 0L;

        String ip = "";
        if (node.hasNonNull("ip") && !node.get("ip").asText().isEmpty()) {
            ip = node.get("ip").asText();
        } else if (node.hasNonNull("address") && !node.get("address").asText().isEmpty()) {
            ip = node.get("address").asText();
        }

        int port = node.hasNonNull("port") ? node.get("port").asInt() : 0;

        CandidateType type = CandidateType.HOST_CANDIDATE;
        if (node.hasNonNull("type")) {
            String typeStr = node.get("type").asText().trim().toLowerCase();
            type = switch (typeStr) {
                case "host", "host_candidate", "local", "local_candidate" -> CandidateType.HOST_CANDIDATE;
                case "srflx", "server_reflexive_candidate", "stun", "stun_candidate" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
                case "prflx", "peer_reflexive_candidate" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
                case "relay", "relayed_candidate" -> CandidateType.RELAYED_CANDIDATE;
                default -> CandidateType.HOST_CANDIDATE;
            };
        }

        int generation = node.hasNonNull("generation") ? node.get("generation").asInt() : 0;
        String id = node.hasNonNull("id") ? node.get("id").asText() : "0";
        String relAddr = null;
        if (node.hasNonNull("relAddr") && !node.get("relAddr").asText().isEmpty()) {
            relAddr = node.get("relAddr").asText();
        } else if (node.hasNonNull("relatedAddress") && !node.get("relatedAddress").asText().isEmpty()) {
            relAddr = node.get("relatedAddress").asText();
        }

        int relPort = 0;
        if (node.hasNonNull("relPort")) {
            relPort = node.get("relPort").asInt();
        } else if (node.hasNonNull("relatedPort")) {
            relPort = node.get("relatedPort").asInt();
        }

        return new CandidatePacket(
                foundation, protocol, priority, ip, port, type, generation, id, relAddr, relPort, adapter);
    }
}
