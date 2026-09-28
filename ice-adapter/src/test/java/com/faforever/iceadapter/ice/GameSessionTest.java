package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.RpcConnection;
import com.faforever.iceadapter.signaling.RpcSignalingProvider;
import com.faforever.iceadapter.signaling.SignalingProvider;
import com.faforever.iceadapter.webrtc.WebRtcConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameSessionTest {

    @AfterEach
    void tearDown() {
        // Cleanup singleton if initialized
        WebRtcConnectionFactory.getInstance().shutdown();
    }

    @Test
    void testCustomWebRtcConnectionFactoryIsPreserved() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();
        WebRtcConnectionFactory customFactory = mock(WebRtcConnectionFactory.class);

        GameSession session = new GameSession(options, customFactory, rpcConnection);

        assertSame(customFactory, session.getWebRtcConnectionFactory());
        session.close();
    }

    @Test
    void testDefaultWebRtcConnectionFactoryUsesSingleton() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();

        GameSession session = new GameSession(rpcConnection, options);

        assertSame(WebRtcConnectionFactory.getInstance(), session.getWebRtcConnectionFactory());
        session.close();
    }

    @Test
    void testDefaultSignalingProviderIsRpcSignalingProvider() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();

        GameSession session = new GameSession(rpcConnection, options);

        assertInstanceOf(RpcSignalingProvider.class, session.getSignalingProvider());
        session.close();
    }

    @Test
    void testCustomSignalingProviderPreservedAndClosed() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();
        WebRtcConnectionFactory customFactory = mock(WebRtcConnectionFactory.class);
        SignalingProvider signalingProvider = mock(SignalingProvider.class);

        GameSession session = new GameSession(options, customFactory, rpcConnection, signalingProvider);

        assertSame(signalingProvider, session.getSignalingProvider());
        session.close();
        verify(signalingProvider).close();
    }

    @Test
    void testSignalingProviderNotifiedOnPeerDisconnected() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();
        options.setId(1);
        WebRtcConnectionFactory customFactory = mock(WebRtcConnectionFactory.class);
        SignalingProvider signalingProvider = mock(SignalingProvider.class);

        GameSession session = new GameSession(options, customFactory, rpcConnection, signalingProvider);
        session.connectToPeer("2", 2, true, 0, AllowCombination.ALL);

        session.disconnectFromPeer(2);

        verify(signalingProvider).onPeerDisconnected(2);
        session.close();
    }
}
