package com.faforever.iceadapter.ice.peer.modules.relay.auto;

import com.faforever.iceadapter.dto.FullRelayMessage;
import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerSenderModule;
import com.faforever.iceadapter.util.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.faforever.iceadapter.ice.peer.modules.other.PeerConnectivityCheckerModule.COMMAND_ECHO;

@Slf4j
public class RelayPeerToPeerSenderModule extends PeerToPeerSenderModule {

    public static final char COMMAND_AUTO_RELAY = 'R';

    public RelayPeerToPeerSenderModule(Peer peer) {
        super(peer);
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        if (peer.isCustomUdpTransport()) {
            return;
        }
        if (data[0] == COMMAND_AUTO_RELAY || data[0] == COMMAND_ECHO) {
            if (peer.isConnected()) {
                sendDirect(data);
            }
            return;
        }

        if (peer.isSendDirectAndRelay()) {
            if (peer.isConnected() && peer.existBestRelays()) {
                sendDirect(data);
                trySendRelay(data);
            } else if (peer.isConnected()) {
                sendDirect(data);
            } else if (peer.existBestRelays()) {
                trySendRelay(data);
            }
            return;
        }

        if (!peer.isConnected() || !sendDirect(data)) {
            trySendRelay(data);
        }
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        if (peer.isCustomUdpTransport()) {
            return;
        }

        if (!peer.isSupportCommand() && !force) {
            return;
        }
        byte[] data = command.bytes();
        if (command.isOnlyDirect()) {
            sendDirect(data);
            return;
        }
        if (!peer.isConnected() || !sendDirect(data)) {
            trySendRelay(data);
        }
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (data[0] != COMMAND_AUTO_RELAY) {
            return;
        }
        handleRelayMsg(data);
    }

    private void handleRelayMsg(byte[] data) {
        FullRelayMessage relayMessage = FullRelayMessage.fromBytes(data, 1, data.length - 1);
        if (relayMessage == null) {
            log.warn("AutoRelay: message is null from peer {}", peer.getPeerIdentifier());
            return;
        }

        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return;
        }

        if (relayMessage.targetId() == peer.getFromId()) {
            Peer fromMsg = gameSession.getPeer(relayMessage.fromId()).orElse(null);
            if (fromMsg == null) {
                return;
            }

            fromMsg.setRelayLastPacketReceived(System.currentTimeMillis());
            fromMsg.handleData(relayMessage.data());
            return;
        }

        Peer target = gameSession.getPeer(relayMessage.targetId()).orElse(null);
        if (target == null) {
            return;
        }
        target.sendToPeer(data);
    }

    private void trySendRelay(byte[] data) {
        if (!isEnabled()) {
            return;
        }

        if (!peer.isSupportCommand()) {
            sendDirect(data);
            return;
        }

        Peer relay = getPeerForRelay();

        if (relay == null) {
            return;
        }

        FullRelayMessage relayMessage = new FullRelayMessage(peer.getFromId(), peer.getRemoteId(), data);
        byte[] messageBytes = relayMessage.toBytes((byte) COMMAND_AUTO_RELAY);
        relay.sendToPeer(messageBytes);
    }

    private Peer getPeerForRelay() {
        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return null;
        }

        List<Integer> ids = peer.getBestRelays();
        if (CollectionUtils.isEmpty(ids)) {
            return null;
        }

        for (Integer id : peer.getBestRelays()) {
            Peer relay = gameSession.getPeer(id).orElse(null);

            if (relay == null || !relay.isConnected()) {
                continue;
            }
            return relay;
        }

        return null;
    }
}
