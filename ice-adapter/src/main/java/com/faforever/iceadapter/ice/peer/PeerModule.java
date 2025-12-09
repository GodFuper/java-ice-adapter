package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.*;
import com.faforever.iceadapter.services.IceAsync;
import kotlin.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Getter
public enum PeerModule implements Comparator<PeerModule> {
    EVENT_BUS(0, components -> {
        Peer peer = components.getFirst();
        IceAsync async = components.getSecond();
        return new EventBusModule(peer, async);
    }),
    FA_SOCKET_MODULE(1, components -> {
        Peer peer = components.getFirst();
        ModuleBase module = new FASocketModule(peer);
        module.start();
        return module;
    }),
    MULTI_PAIRS(components -> {
        Peer peer = components.getFirst();
        return new UseCustomPairModule(peer);
    }),
    FA_SENDER_MODULE(components -> {
        Peer peer = components.getFirst();
        return new FASenderModule(peer);
    }),
    FA_REPEATER_MODULE(components -> {
        Peer peer = components.getFirst();
        IceAsync async = components.getSecond();
        FAListenerModule module = new FAListenerModule(peer, async);
        module.start();
        return module;
    }),
    ICE_TO_FA_SENDER(components -> {
        Peer peer = components.getFirst();
        return new IceToFASenderModule(peer);
    }),
    ICE_TO_ICE_SENDER(components -> {
        Peer peer = components.getFirst();
        return new IceSenderModule(peer);
    }),
    ICE_LISTENER_MODULE(components -> {
        Peer peer = components.getFirst();
        return new IceListenerModule(peer);
    }),
    CONNECTION_CHECKER_MODULE(components -> {
        Peer peer = components.getFirst();
        IceAsync async = components.getSecond();
        return new PeerConnectivityCheckerModule(peer, async);
    });

    @Getter
    private static final List<PeerModule> sortedModules = Stream.of(PeerModule.values())
            .sorted()
            .toList();

    private final int priority;
    private final Function<Pair<Peer, IceAsync>, ModuleBase> createModule;

    PeerModule(Function<Pair<Peer, IceAsync>, ModuleBase> createModule) {
        this(10, createModule);
    }

    public ModuleBase createModule(Pair<Peer, IceAsync> components) {
        ModuleBase module = createModule.apply(components);
        module.init();
        return module;
    }

    @Override
    public int compare(PeerModule o1, PeerModule o2) {
        return Comparator.comparingInt(PeerModule::getPriority)
                .thenComparing(PeerModule::getPriority).compare(o1, o2);
    }
}
