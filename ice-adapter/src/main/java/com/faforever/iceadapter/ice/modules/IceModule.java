package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IcePeerAdapter;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.Peer;
import kotlin.Triple;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.function.Function;

@RequiredArgsConstructor
@Getter
public enum IceModule {
    FA_SOCKET_MODULE(triple -> {
        Peer peer = triple.getFirst();
        ModuleBase module = new FASocketModule(peer);
        module.start();
        return module;
    }),
    FA_REPEATER_MODULE(triple -> {
        Peer peer = triple.getFirst();
        IcePeerAdapter peerAdapter = triple.getThird();
        FARepeaterModule module = new FARepeaterModule(peerAdapter, peer);
        module.start();
        return module;
    }),
    ICE_LISTENER_MODULE(triple -> {
        Peer peer = triple.getFirst();
        IceGameSession gameSession = triple.getSecond();
        IcePeerAdapter peerAdapter = triple.getThird();
        return new IceListenerModule(gameSession, peerAdapter, peer);
    }),
    CONNECTION_CHECKER_MODULE(triple -> {
        Peer peer = triple.getFirst();

        IcePeerAdapter peerAdapter = triple.getThird();
        return new PeerConnectivityCheckerModule(peerAdapter, peer);
    });

    private final Function<Triple<Peer, IceGameSession, IcePeerAdapter>, ModuleBase> createModule;
}
