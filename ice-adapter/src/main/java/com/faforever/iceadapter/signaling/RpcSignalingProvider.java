package com.faforever.iceadapter.signaling;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.RpcConnection;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcSignalingProvider implements SignalingProvider {

    private final Consumer<CandidatesMessage> sender;

    public RpcSignalingProvider(RPCService rpcService) {
        this(rpcService != null ? rpcService::onIceMsg : msg -> {});
    }

    public RpcSignalingProvider(RpcConnection rpcConnection) {
        this(rpcConnection != null ? rpcConnection::sendToRpc : msg -> {});
    }

    public RpcSignalingProvider(Consumer<CandidatesMessage> sender) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
    }

    @Override
    public void init() {
        log.debug("Initializing RpcSignalingProvider");
    }

    @Override
    public void sendSignalingMessage(CandidatesMessage message) {
        log.debug("Sending signaling message via RPC: {}", message);
        sender.accept(message);
    }

    @Override
    public void close() {
        log.debug("Closing RpcSignalingProvider");
    }
}
