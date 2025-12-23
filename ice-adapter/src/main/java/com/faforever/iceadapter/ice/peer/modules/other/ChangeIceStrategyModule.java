package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;

@Slf4j
@RequiredArgsConstructor
public class ChangeIceStrategyModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

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
            return;
        }
        agent.setNominationStrategy(peer.getAgentStrategy().getStrategy());
    }

}
