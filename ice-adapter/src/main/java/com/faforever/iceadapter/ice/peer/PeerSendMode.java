package com.faforever.iceadapter.ice.peer;

/**
 * Defines how a peer sends data: through standard UDP, KCP, or both.
 */
public enum PeerSendMode {
    /**
     * Send only through PeerToPeerSenderModule (standard UDP/relay).
     * This is the default mode.
     */
    DIRECT_ONLY,

    /**
     * Send through both PeerToPeerSenderModule AND KcpPeerToPeerSenderModule.
     * Data is sent via standard UDP and KCP simultaneously.
     */
    BOTH,

    /**
     * Send only through KcpPeerToPeerSenderModule (KCP overlay).
     * The standard UDP/relay path is disabled.
     */
    KCP_ONLY
}
