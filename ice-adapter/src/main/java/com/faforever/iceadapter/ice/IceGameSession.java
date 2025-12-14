package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IceGameSession {

    Map<Integer, Peer> getPeers();

    Optional<Peer> getPeer(int peerId);

    List<IceServer> getFilteredIceServers();

    List<IceServer> getIceServers();

    boolean isGameEnded();

    int getLobbyPort();

    int getMyId();

    void sendToRpc(CandidatesMessage message);

    void onConnected(Peer peer, boolean connected);

    void showMessage(String message);
}
