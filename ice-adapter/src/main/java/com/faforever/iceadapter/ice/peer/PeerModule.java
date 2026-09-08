package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.fa.FaToPeerModule;
import com.faforever.iceadapter.ice.peer.modules.fa.PeerToFaModule;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import com.faforever.iceadapter.ice.peer.modules.info.RttCalculateModule;
import com.faforever.iceadapter.ice.peer.modules.other.*;
import com.faforever.iceadapter.ice.peer.modules.relay.auto.RelayBestRttPeerCheckerModule;
import com.faforever.iceadapter.ice.peer.modules.relay.auto.RelayPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.relay.manual.RelayClientModule;
import com.faforever.iceadapter.ice.peer.modules.relay.manual.RelayServerModule;
import com.faforever.iceadapter.ice.peer.modules.webrtc.WebRtcPeerToPeerListenerModule;
import com.faforever.iceadapter.ice.peer.modules.webrtc.WebRtcPeerToPeerSenderModule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    PEER_TO_PEER_SENDER(RelayPeerToPeerSenderModule::new),
    PEER_LISTENER_MODULE(PeerToPeerListenerModule::new),
    CONNECTION_CHECKER_MODULE(PeerConnectivityCheckerModule::new),
    AUTO_SETTING_ALLOW_CANDIDATE(AutoSettingAllowCandidates::new),
    COMMAND_EXECUTE(CommandModule::new),
    RELAY_CLIENT_MODULE(RelayClientModule::new),
    RELAY_SERVER_MODULE(RelayServerModule::new),
    AUTO_RELAY_CALCULATE_RTT(RelayBestRttPeerCheckerModule::new),
    KCP_OFFERER_PEER_TO_PEER_TRANSPORT(KcpPeerToPeerSenderModule::new),
    CHANGE_AGENT_STRATEGY(ChangeIceStrategyModule::new),
    INFO_STATUS_MODULE(InfoStatusModule::new),
    PEER_TURN_REFRESHER_MODULE(PeerTurnRefresherModule::new),
    PAIR_SELECTOR(PairSelectorModule::new),
    WEBRTC_PEER_TO_PEER_SENDER(WebRtcPeerToPeerSenderModule::new),
    WEBRTC_PEER_TO_PEER_LISTENER(WebRtcPeerToPeerListenerModule::new);

    @Getter
    private static final List<PeerModule> sortedModules =
            Stream.of(PeerModule.values()).sorted().toList();

    private static final Set<PeerModule> MODULES_FOR_SERVER = Set.of(
            EVENT_BUS, RELAY_SERVER_MODULE, PEER_LISTENER_MODULE, PEER_TO_PEER_SENDER, PEER_TURN_REFRESHER_MODULE);

    @Getter
    private static final Set<PeerModule> modulesDisableForServer = Stream.of(PeerModule.values())
            .filter(m -> !MODULES_FOR_SERVER.contains(m))
            .collect(Collectors.toSet());

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
                .thenComparing(PeerModule::getPriority)
                .compare(o1, o2);
    }
}
