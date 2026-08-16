package com.faforever.iceadapter.ice.turn;

import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvest;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

@Slf4j
public class ModifyTurnCandidateHarvester extends TurnCandidateHarvester {

    public ModifyTurnCandidateHarvester(TransportAddress turnServer, LongTermCredential longTermCredential) {
        super(turnServer, longTermCredential);
    }

    @Override
    protected TurnCandidateHarvest createHarvest(HostCandidate hostCandidate) {
        return new ModifyTurnCandidateHarvest(this, hostCandidate);
    }
}
