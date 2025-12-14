package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;

@Slf4j
@RequiredArgsConstructor
public class ChangeIceStrategyModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

    private boolean running = false;

    @Setter
    private boolean enabled = true;

    @Override
    public void init() {
        if (isStartCondition()) {
            peer.addEventListener(this);
        }
    }

    private boolean isStartCondition() {
        return enabled && peer.isLocalOffer();
    }

    @Override
    public void onAgentChange(Peer peer, Agent agent) {
        if (peer.isClosing() || !enabled) {
            return;
        }

        if (agent == null) {
            running = false;
            return;
        }
        running = true;
        agent.setNominationStrategy(peer.getAgentStrategy().getStrategy());
    }

}
