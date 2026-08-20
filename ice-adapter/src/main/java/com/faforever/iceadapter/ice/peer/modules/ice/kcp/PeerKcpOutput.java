package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.Peer;
import io.jpower.kcp.netty.Kcp;
import io.jpower.kcp.netty.KcpOutput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.io.IOException;
import java.util.Optional;

import static com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER;


@Slf4j
@RequiredArgsConstructor
@Data
public class PeerKcpOutput implements KcpOutput {
    private final Peer peer;

    private volatile Kcp kcpOut;

    @Override
    public void out(ByteBuf data, Kcp kcp) {
        Component component = peer.getComponent();
        if (component == null) {
            return;
        }

        if (kcpOut == null) {
            kcpOut = kcp;
        }

        // Called by KCP when raw UDP bytes need to be sent
        // Prepend 'u' marker to identify KCP data packets
        int totalLength = data.readableBytes() + 1;
        ByteBuf packet = Unpooled.buffer(totalLength);
        packet.writeByte(KCP_PROTOCOL_MARKER);
        packet.writeBytes(data);
        try {
            component.send(packet.array(), 0, totalLength);
        } catch (IOException e) {
            log.error("KCP output send failed {}", peer.getPeerIdentifier(), e);
        } finally {
            packet.release();
        }
    }

    public Optional<Kcp> getKcp() {
        return Optional.ofNullable(kcpOut);
    }
}
