package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcp;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpOutput;
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
public class PeerKcpOutput implements MyKcpOutput {
    private final Peer peer;
    private final byte channel;

    private volatile MyKcp kcpOut;

    @Override
    public void out(ByteBuf data, MyKcp kcp) {
        Component component = peer.getComponent();
        if (component == null) {
            data.release();
            return;
        }

        if (kcpOut == null) {
            kcpOut = kcp;
        }

        // Called by KCP when raw UDP bytes need to be sent
        // Prepend 'u' marker and channel byte to identify KCP data packets
        int totalLength = data.readableBytes() + 2;
        ByteBuf packet = Unpooled.buffer(totalLength);
        packet.writeByte(KCP_PROTOCOL_MARKER);
        packet.writeByte(channel);
        packet.writeBytes(data);
        data.release();
        try {
            component.send(packet.array(), 0, totalLength);
        } catch (IOException e) {
            log.error("KCP output send failed {}", peer.getPeerIdentifier(), e);
        } finally {
            packet.release();
        }
    }

    public Optional<MyKcp> getKcp() {
        return Optional.ofNullable(kcpOut);
    }

    public void close() {
        // MyKcp doesn't have a release method
    }
}
