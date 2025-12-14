package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import javafx.collections.ObservableList;

/**
 * Интерфейс или заглушка для основного адаптера.
 * Предполагается, что реальный класс (например, IceAdapter) реализует эти методы.
 */
public interface UIAdapter {

    String getVersion();

    String getUsername();

    int getUserId();

    int getRpcPort();

    int getGpgNetPort();

    int getLobbyPort();

    String getRpcServerStatus();

    String getRpcClientStatus();

    String getGpgNetServerStatus();

    String getGpgNetClientStatus();

    String getGameState();

    ObservableList<PeerInfo> getPeerInfoList();

    String getLogBuffer();

    void reconnect(PeerInfo peer);

    void setRulesConnection(PeerInfo peer, boolean allowHost, boolean allowReflexive, boolean allowRelay);

    void setStrategy(PeerInfo peer, IceAgentStrategy newStrategy);

    void shutdown();
}
