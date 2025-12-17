package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class AutoSettingAllowCandidates implements ModuleBase, PeerEventListener {
    private static final List<AllowCombination> combinations = List.of(AllowCombination.values());

    private final Peer peer;

    @Setter
    private boolean enabled = true;
    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public void init() {
        if (enabled) {
            peer.addEventListener(this);
        }
    }

    @Override
    public void onAgentChange(Peer peer, Agent agent) {
        if (!enabled) {
            return;
        }

        if (agent != null) {
            changeCombination();
        }
    }

    private void changeCombination() {
        int id = index.getAndIncrement();
        if (id >= combinations.size()) {
            index.set(0);
            id = 0;
        }
        AllowCombination combination = combinations.get(id);
        peer.setCombination(combination);
    }
}
