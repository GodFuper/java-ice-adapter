package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.harvest.UPNPHarvester;

@Slf4j
@RequiredArgsConstructor
public class UPNPSupport implements ModuleBase, PeerEventListener {

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onAgentChange(Peer peer, Agent agent) {
        if (agent != null) {
            addUpnp(agent);
        }
    }

    private void addUpnp(Agent agent) {
        agent.addCandidateHarvester(new UPNPHarvester());
    }
}
