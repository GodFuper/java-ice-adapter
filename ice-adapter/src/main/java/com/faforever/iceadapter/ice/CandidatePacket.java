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
        int relPort,
        String adapter)
        implements Comparable<CandidatePacket> {

    public static final String ADAPTER_FAF_ICE_ADAPTER = "faf-ice-adapter";

    public CandidatePacket(
            String foundation,
            String protocol,
            long priority,
            String ip,
            int port,
            CandidateType type,
            int generation,
            String id,
            String relAddr,
            int relPort) {
        this(foundation, protocol, priority, ip, port, type, generation, id, relAddr, relPort, ADAPTER_FAF_ICE_ADAPTER);
    }

    @Override
    public int compareTo(CandidatePacket o) {
        return (int) (o.priority - this.priority);
    }
}
