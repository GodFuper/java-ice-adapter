package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.connection;

/**
 * Connection state machine for the Custom Reliable UDP transport.
 */
public enum ConnectionState {
    IDLE,
    CONNECTING,
    ACTIVE,
    DISCONNECTED,
    CLOSED
}
