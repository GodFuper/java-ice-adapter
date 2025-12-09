package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;

import java.util.List;
import java.util.Map;

public interface IceGameSession {

    Map<Integer, Peer> getPeers();

    List<IceServer> getFilteredIceServers();

    List<IceServer> getIceServers();

    boolean isGameEnded();

    int getLobbyPort();

    int getMyId();

    void sendToRpc(CandidatesMessage message);

    void onConnected(Peer peer, boolean connected);

    void showMessage(String message);
}
