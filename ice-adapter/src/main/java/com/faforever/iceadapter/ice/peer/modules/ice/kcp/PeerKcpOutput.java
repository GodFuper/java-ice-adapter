package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.IceKcp;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.IceKcpOutput;
import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.io.IOException;

import static com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER;

@Slf4j
@RequiredArgsConstructor
@Data
public class PeerKcpOutput implements IceKcpOutput {
    private final Peer peer;

    @Override
    public void out(ByteBuf data, IceKcp kcp) {
        Component component = peer.getComponent();
        if (component == null) {
            data.release();
            return;
        }

        // Called by KCP when raw UDP bytes need to be sent
        // Prepend KCP protocol marker to identify KCP data packets
        int totalLength = data.readableBytes() + 1;
        byte[] packetBytes = new byte[totalLength];
        packetBytes[0] = KCP_PROTOCOL_MARKER;
        data.getBytes(data.readerIndex(), packetBytes, 1, data.readableBytes());
        data.release();
        try {
            component.send(packetBytes, 0, totalLength);
        } catch (IOException e) {
            log.error("KCP output send failed {}", peer.getPeerIdentifier(), e);
        }
    }
}
