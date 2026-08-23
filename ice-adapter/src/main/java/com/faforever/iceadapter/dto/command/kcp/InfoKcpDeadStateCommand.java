package com.faforever.iceadapter.dto.command.kcp;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class InfoKcpDeadStateCommand extends CommandBase {

    private byte conv;

    @Override
    public void execute(Peer peer) {
        if (peer == null) {
            log.warn("peer is null");
            return;
        }

        if (!peer.isLocalOffer()) {
            log.warn("peer {} is not local offer", peer.getPeerIdentifier());
            return;
        }

        KcpPeerToPeerSenderModule module = peer.getModule(PeerModule.KCP_OFFERER_PEER_TO_PEER_TRANSPORT,
                KcpPeerToPeerSenderModule.class).orElse(null);
        if (module == null) {
            log.warn("Module KcpOffererPeerToPeerSenderModule not found on peer {}", peer.getPeerIdentifier());
            return;
        }
        module.restartKcp(conv);
    }
}
