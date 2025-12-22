package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.fa.FaToPeerModule;
import com.faforever.iceadapter.ice.peer.modules.fa.PeerToFaModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.info.RttCalculateModule;
import com.faforever.iceadapter.ice.peer.modules.other.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Getter
public enum PeerModule implements Comparator<PeerModule> {
    EVENT_BUS(0, EventBusModule::new),
    FA_SOCKET_MODULE(1, peer -> {
        var module = new FASocketModule(peer);
        module.firstStart();
        return module;
    }),
    FA_SENDER_MODULE(PeerToFaModule::new),
    FA_REPEATER_MODULE(peer -> {
        var module = new FaToPeerModule(peer);
        module.start();
        return module;
    }),
    CALCULATE_RTT(RttCalculateModule::new),
    ICE_TO_ICE_SENDER(PeerToPeerSenderModule::new),
    ICE_LISTENER_MODULE(PeerToPeerListenerModule::new),
    CONNECTION_CHECKER_MODULE(PeerConnectivityCheckerModule::new),
    AUTO_SETTING_ALLOW_CANDIDATE(AutoSettingAllowCandidates::new),
    UPNP_SUPPORT(UPNPSupport::new),
    CHANGE_AGENT_STRATEGY(ChangeIceStrategyModule::new);

    @Getter
    private static final List<PeerModule> sortedModules = Stream.of(PeerModule.values())
            .sorted()
            .toList();

    private final int priority;
    private final Function<Peer, ModuleBase> createModule;

    PeerModule(Function<Peer, ModuleBase> createModule) {
        this(10, createModule);
    }

    public ModuleBase createModule(Peer peer) {
        ModuleBase module = createModule.apply(peer);
        module.init();
        return module;
    }

    @Override
    public int compare(PeerModule o1, PeerModule o2) {
        return Comparator.comparingInt(PeerModule::getPriority)
                .thenComparing(PeerModule::getPriority).compare(o1, o2);
    }
}
