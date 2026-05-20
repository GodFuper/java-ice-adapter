package com.faforever.iceadapter.dto.command.info;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
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
public class InfoRelayStatusCommand extends CommandBase {

    private boolean status;

    @Override
    public void execute(Peer peer) {
        if (peer == null) {
            log.warn("peer is null");
            return;
        }

        if (peer instanceof MainPeer mainPeer) {
            mainPeer.setAllowRelay(status);
            if (mainPeer.getVersion() <= 1) {
                mainPeer.setVersion(2);
            }
        }
    }

}
