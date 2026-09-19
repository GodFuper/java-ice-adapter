package com.faforever.iceadapter.ice;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Represents a candidate to be sent/received via IceMessage.
 * Serializes both standard WebRTC candidate string and legacy fields.
 */
@JsonSerialize(using = CandidatePacketSerializer.class)
@JsonDeserialize(using = CandidatePacketDeserializer.class)
public record CandidatePacket(
        String foundation,
        String protocol,
        long priority,
        String ip,
        int port,
        CandidateType type,
        int generation,
        String id,
        String relAddr,
        int relPort)
        implements Comparable<CandidatePacket> {
    @Override
    public int compareTo(CandidatePacket o) {
        return (int) (o.priority - this.priority);
    }
}
