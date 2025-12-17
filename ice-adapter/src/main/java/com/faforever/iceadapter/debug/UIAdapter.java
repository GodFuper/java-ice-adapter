package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import javafx.collections.ObservableList;

/**
 * Interface for UI components to interact with the ICE adapter and display connection/debug information.
 * Provides methods to retrieve application state, peer information, and control ICE agent behavior.
 */
public interface UIAdapter {

    /**
     * Returns the current version of the ICE adapter.
     *
     * @return version string
     */
    String getVersion();

    /**
     * Returns the username of the connected user.
     * @return username
     */
    String getUsername();

    /**
     * Returns the user ID of the connected user.
     * @return user ID
     */
    int getUserId();

    /**
     * Returns the RPC server port used by the adapter.
     * @return RPC port number
     */
    int getRpcPort();

    /**
     * Returns the GPGNet server port used by the adapter.
     * @return GPGNet port number
     */
    int getGpgNetPort();

    /**
     * Returns the lobby server port used by the adapter.
     * @return lobby port number
     */
    int getLobbyPort();

    /**
     * Returns the current status of the RPC server.
     * @return RPC server status as string
     */
    String getRpcServerStatus();

    /**
     * Returns the current status of the RPC client.
     * @return RPC client status as string
     */
    String getRpcClientStatus();

    /**
     * Returns the current status of the GPGNet server.
     * @return GPGNet server status as string
     */
    String getGpgNetServerStatus();

    /**
     * Returns the current status of the GPGNet client.
     * @return GPGNet client status as string
     */
    String getGpgNetClientStatus();

    /**
     * Returns the current game state.
     * @return game state as string
     */
    String getGameState();

    /**
     * Returns an observable list of peer connection details.
     * This list can be bound to UI components for real-time updates.
     * @return observable list of peer info objects
     */
    ObservableList<PeerInfo> getPeerInfoList();

    /**
     * Requests reconnection to the specified peer.
     * @param peer the peer to reconnect to
     */
    void reconnect(PeerInfo peer);

    /**
     * Sets the allowed combination mode for the specified peer.
     *
     * @param peer        the target peer
     * @param combination the combination mode to allow
     */
    void setAllowCombination(PeerInfo peer, AllowCombination combination);

    /**
     * Changes the ICE agent strategy for the specified peer.
     * @param peer the target peer
     * @param newStrategy the new strategy to apply
     */
    void setStrategy(PeerInfo peer, IceAgentStrategy newStrategy);

    /**
     * Initiates a graceful shutdown of the adapter and all associated components.
     */
    void shutdown();
}
