package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.ServerPeer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IceGameSession {

    Map<Integer, Peer> getPeers();

    List<ServerPeer> getServerPeers();

    Optional<Peer> getPeer(int peerId);

    List<IceServer> getIceServers();

    boolean isGameEnded();

    int getLobbyPort();

    IceOptions getOptions();
}
