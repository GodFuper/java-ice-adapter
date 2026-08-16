package com.faforever.iceadapter.ice.turn;

import lombok.extern.slf4j.Slf4j;
import org.ice4j.StunException;
import org.ice4j.TransportAddress;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvest;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.message.MessageFactory;
import org.ice4j.message.Request;

@Slf4j
public class ModifyTurnCandidateHarvest extends TurnCandidateHarvest {

    public ModifyTurnCandidateHarvest(TurnCandidateHarvester harvester, HostCandidate hostCandidate) {
        super(harvester, hostCandidate);
    }

    protected RelayedCandidate createRelayedCandidate(
            TransportAddress transportAddress, TransportAddress mappedAddress) {
        return new ModifyRelayedCandidate(transportAddress, this, mappedAddress);
    }

    public void sendRefresh() throws StunException {
        Request refreshRequest = MessageFactory.createRefreshRequest(
                600); // Maximum lifetime of turn is 600 seconds (10 minutes), server may limit this even further
        sendRequest(refreshRequest, false, null);
    }
}
