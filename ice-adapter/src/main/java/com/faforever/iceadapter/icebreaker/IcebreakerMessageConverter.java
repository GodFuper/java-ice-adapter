package com.faforever.iceadapter.icebreaker;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

@UtilityClass
public class IcebreakerMessageConverter {

    private static final JsonNodeFactory JSON_NODE_FACTORY = JsonNodeFactory.instance;

    public static EventMessageDto.Candidates toIcebreakerCandidates(long gameId, CandidatesMessage message) {
        ObjectNode sessionNode = JSON_NODE_FACTORY.objectNode();
        sessionNode.put("type", message.isOffer() ? "offer" : "answer");
        sessionNode.put("sdp", message.password() != null ? message.password() : "");

        ArrayNode candidatesArray = JSON_NODE_FACTORY.arrayNode();
        if (message.candidates() != null) {
            for (CandidatePacket candidate : message.candidates()) {
                ObjectNode candidateNode = JSON_NODE_FACTORY.objectNode();
                candidateNode.put("foundation", candidate.foundation() != null ? candidate.foundation() : "");
                candidateNode.put("priority", candidate.priority());
                candidateNode.put("address", candidate.ip() != null ? candidate.ip() : "");
                candidateNode.put(
                        "protocol",
                        candidate.protocol() != null ? candidate.protocol().toLowerCase(Locale.ROOT) : "udp");
                candidateNode.put("port", candidate.port());
                candidateNode.put(
                        "type", candidate.type() != null ? candidate.type().getName() : "host");
                candidateNode.put("component", 1);
                candidatesArray.add(candidateNode);
            }
        }

        return new EventMessageDto.Candidates(
                gameId, message.srcId(), (long) message.destId(), sessionNode, candidatesArray);
    }

    public static CandidatesMessage fromIcebreakerCandidates(EventMessageDto.Candidates dto) {
        JsonNode sessionNode = dto.session();
        String ufrag = sessionNode != null ? sessionNode.path("type").asText("offer") : "offer";
        String sdp = sessionNode != null ? sessionNode.path("sdp").asText("") : "";

        List<CandidatePacket> candidatePackets = new ArrayList<>();
        JsonNode candidatesNode = dto.candidates();
        if (candidatesNode != null && candidatesNode.isArray()) {
            for (JsonNode node : candidatesNode) {
                String foundation = node.path("foundation").asText("");
                String protocol = node.path("protocol").asText("udp").toLowerCase(Locale.ROOT);
                long priority = node.path("priority").asLong(0L);
                String address = node.path("address").asText("");
                int port = node.path("port").asInt(0);
                String typeStr = node.path("type").asText("");
                CandidateType type = parseCandidateType(typeStr);

                candidatePackets.add(
                        new CandidatePacket(foundation, protocol, priority, address, port, type, 0, "0", null, 0));
            }
        }

        int destId = dto.recipientId() != null ? dto.recipientId().intValue() : 0;
        return new CandidatesMessage((int) dto.senderId(), destId, sdp, ufrag, candidatePackets);
    }

    private static CandidateType parseCandidateType(String typeStr) {
        if (typeStr == null) {
            return CandidateType.HOST_CANDIDATE;
        }
        return switch (typeStr.toLowerCase(Locale.ROOT)) {
            case "host" -> CandidateType.HOST_CANDIDATE;
            case "srflx" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
            case "relay" -> CandidateType.RELAYED_CANDIDATE;
            case "prflx" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
            default -> CandidateType.HOST_CANDIDATE;
        };
    }
}
