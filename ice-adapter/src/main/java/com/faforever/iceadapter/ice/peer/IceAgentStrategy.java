package com.faforever.iceadapter.ice.peer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.ice4j.ice.NominationStrategy;

@RequiredArgsConstructor
@Getter
public enum IceAgentStrategy {
    FIRST("The first successful (Default)", NominationStrategy.NOMINATE_FIRST_VALID),
    AUTO_PRIORITY("Auto-selection using priorities", NominationStrategy.NOMINATE_HIGHEST_PRIO),
    FIRST_HOST_OR_REFLEXIVE("Preference for Host or Reflexive", NominationStrategy.NOMINATE_FIRST_HOST_OR_REFLEXIVE_VALID);
    private final String name;
    private final NominationStrategy strategy;

    @Override
    public String toString() {
        return name;
    }
}
