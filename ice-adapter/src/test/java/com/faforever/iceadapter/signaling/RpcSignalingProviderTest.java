package com.faforever.iceadapter.signaling;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.RpcConnection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RpcSignalingProviderTest {

    @Test
    @DisplayName("RpcSignalingProvider should delegate to RpcConnection")
    void testDelegateToRpcConnection() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        RpcSignalingProvider provider = new RpcSignalingProvider(rpcConnection);

        CandidatesMessage msg = new CandidatesMessage(1, 2, "sdp", "offer", List.of());
        provider.sendSignalingMessage(msg);

        verify(rpcConnection).sendToRpc(msg);
    }

    @Test
    @DisplayName("RpcSignalingProvider should delegate to RPCService")
    void testDelegateToRpcService() {
        RPCService rpcService = mock(RPCService.class);
        RpcSignalingProvider provider = new RpcSignalingProvider(rpcService);

        CandidatesMessage msg = new CandidatesMessage(1, 2, "sdp", "offer", List.of());
        provider.sendSignalingMessage(msg);

        verify(rpcService).onIceMsg(msg);
    }

    @Test
    @DisplayName("RpcSignalingProvider should delegate to Consumer")
    void testDelegateToConsumer() {
        AtomicReference<CandidatesMessage> captured = new AtomicReference<>();
        RpcSignalingProvider provider = new RpcSignalingProvider(captured::set);

        CandidatesMessage msg = new CandidatesMessage(1, 2, "sdp", "offer", List.of());
        provider.sendSignalingMessage(msg);

        assertSame(msg, captured.get());
    }
}
