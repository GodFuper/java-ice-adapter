package com.faforever.iceadapter.ice.turn;

import lombok.Getter;
import org.ice4j.TransportAddress;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvest;

public class ModifyRelayedCandidate extends RelayedCandidate {
    @Getter
    private final TurnCandidateHarvest turnCandidateHarvest;

    public ModifyRelayedCandidate(
            TransportAddress transportAddress,
            TurnCandidateHarvest turnCandidateHarvest,
            TransportAddress mappedAddress) {
        super(transportAddress, turnCandidateHarvest, mappedAddress);
        this.turnCandidateHarvest = turnCandidateHarvest;
    }
}
