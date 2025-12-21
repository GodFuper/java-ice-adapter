package com.faforever.iceadapter.ice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents and IceMessage, consists out of candidates and ufrag aswell as password
 */
public record CandidatesMessage(int srcId,
                                int destId,
                                String password,
                                String ufrag,
                                List<CandidatePacket> candidates) {
    public CandidatesMessage {
        candidates = List.copyOf(candidates);
    }

    public String toStrCandidates() {
        if (candidates == null) {
            return "";
        }
        return candidates.stream()
                .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                .collect(Collectors.joining(", "));
    }
}
