package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.KeepAliveStrategy;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.ice4j.ice.NominationStrategy.NOMINATE_HIGHEST_PRIO;

@Slf4j
@RequiredArgsConstructor
public class UseCustomPairModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

    @Getter
    private CandidatePair selectedPair;
    @Getter
    private final Set<CandidatePair> successPairs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private boolean isRunning = false;

    @Getter
    @Setter
    private boolean enabled = false;

    @Override
    public void init() {
        if (enabled) {
            peer.addEventListener(this);
            peer.setKeepAliveStrategy(KeepAliveStrategy.SELECTED_ONLY);
        }
    }

    public boolean isRunning() {
        return isRunning && enabled;
    }

    @Override
    public void onAgentChange(Peer peer, Agent agent) {
        if (peer.isClosing() || !enabled) {
            return;
        }

        if (agent == null) {
            isRunning = false;
            selectedPair = null;
            successPairs.clear();
            return;
        }

        agent.setNominationStrategy(NOMINATE_HIGHEST_PRIO);
        agent.setPerformConsentFreshness(true);
    }

    @Override
    public void onIceMediaStreamChange(Peer peer, IceMediaStream stream) {
        if (peer.isClosing() || !enabled) {
            return;
        }
        if (stream == null) {
            return;
        }
        isRunning = true;
        stream.addPairChangeListener(event -> {
            if (Objects.equals(IceMediaStream.PROPERTY_PAIR_NOMINATED, event.getPropertyName())) {
                Boolean setNominated = (Boolean) event.getNewValue();
                if (setNominated == true) {
                    selectedPair = (CandidatePair) event.getSource();
                    successPairs.add((CandidatePair) event.getSource());
                }
            } else if (Objects.equals(IceMediaStream.PROPERTY_PAIR_VALIDATED, event.getPropertyName())) {
                CandidatePair pair = (CandidatePair) event.getSource();
                Boolean isValid = (Boolean) event.getNewValue();

//                if(isValid) {
//                    successPairs.add(pair);
//                }
            }
//            } else if (Objects.equals(IceMediaStream.PROPERTY_PAIR_STATE_CHANGED, event.getPropertyName())) {
//                CandidatePair pair = (CandidatePair) event.getSource();
//                CandidatePairState newState = (CandidatePairState) event.getNewValue();
//
//                if (CandidatePairState.SUCCEEDED.equals(newState)) {
//                    successPairs.add(pair);
//                } else {
//                    successPairs.remove(pair);
//                }
//            }
        });
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
