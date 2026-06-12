package com.faforever.iceadapter.ice.peer.modules.relay.manual;

import com.faforever.iceadapter.dto.RelayMessage;
import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.dto.command.relay.manual.from_client.*;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerSenderModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.faforever.iceadapter.ice.peer.modules.relay.manual.RelayServerModule.COMMAND_CLIENT;
import static com.faforever.iceadapter.ice.peer.modules.relay.manual.RelayServerModule.COMMAND_SERVER;

@Slf4j
@RequiredArgsConstructor
public class RelayClientModule implements ModuleBase, PeerEventListener {
    private static final int TIMEOUT_BEFORE_START = 500;
    private final Peer peer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public Boolean isEnabled() {
        return peer instanceof MainPeer;
    }

    @Override
    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        if (peer instanceof MainPeer mainPeer) {
            Peer rPeer = mainPeer.getRelayPeer();
            if (rPeer == null) {
                return;
            }

            rPeer.sendCommand(new RpcMessageFromClientPeerCommand(peer.getRemoteId(), message));
        }
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        if (peer instanceof MainPeer mainPeer) {

            Peer rPeer = mainPeer.getRelayPeer();

            if (rPeer == null) {
                return;
            }

            sendRelayMessage(mainPeer, rPeer, data);
        }
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        if (!peer.isSupportCommand() && !force) {
            return;
        }

        if (peer instanceof MainPeer mainPeer) {
            Peer rPeer = mainPeer.getRelayPeer();

            if (rPeer == null) {
                return;
            }

            byte[] data = command.bytes();
            sendRelay(mainPeer, rPeer, data);
        }
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (peer instanceof MainPeer mainPeer) {
            Peer rPeer = mainPeer.getRelayPeer();
            if (rPeer == null) {
                return;
            }
            rPeer.sendCommand(new ConnectionLostRelayServerCommand(mainPeer.getRemoteId(), clearIceState));
        }
    }

    @Override
    public void onCombinationChange(Peer peer, AllowCombination combination) {
        if (peer instanceof MainPeer mainPeer) {
            Peer rPeer = mainPeer.getRelayPeer();
            if (rPeer == null) {
                return;
            }
            rPeer.sendCommand(new SetAllowCombinationCommand(mainPeer.getRemoteId(), mainPeer.getCombination()));
        }
    }

    public void enableClient(MainPeer mainPeer, MainPeer newRelayPeer) {
        MainPeer oldRelayPeer = mainPeer.getRelayPeer();
        if (ObjectUtils.allNotNull(oldRelayPeer, newRelayPeer) && Objects.equals(oldRelayPeer.getRemoteId(), newRelayPeer.getRemoteId())) {
            return;
        }

        if (!newRelayPeer.isAllowRelay()) {
            return;
        }

        if (oldRelayPeer != null) {
            oldRelayPeer.getLinkClient().remove(mainPeer.getRemoteId(), mainPeer);
            oldRelayPeer.sendCommand(new StopRelayServerCommand(mainPeer.getRemoteId()));
            mainPeer.setRelayPeer(null, false);
        }
        mainPeer.setDisableConnectService(true);
        mainPeer.lostConnect(true);

        scheduler.schedule(() -> tryStartRelay(mainPeer, newRelayPeer, 0), TIMEOUT_BEFORE_START, TimeUnit.MILLISECONDS);
    }

    private void tryStartRelay(MainPeer mainPeer, MainPeer newRelayPeer, int iter) {
        if (iter >= 10) {
            log.info("Starting relay server is cancel by timeout {}", mainPeer);
            disableClient(mainPeer);
            return;
        }
        if (mainPeer.getIceState() != null) {
            scheduler.schedule(() -> tryStartRelay(mainPeer, newRelayPeer, iter + 1), TIMEOUT_BEFORE_START, TimeUnit.MILLISECONDS);
            return;
        }

        mainPeer.setRelayPeer(newRelayPeer, false);
        newRelayPeer.getLinkClient().put(mainPeer.getRemoteId(), mainPeer);
        mainPeer.getModule(PeerModule.PEER_TO_PEER_SENDER, PeerToPeerSenderModule.class)
                .ifPresent(PeerToPeerSenderModule::disable);
        newRelayPeer.sendCommand(new StartRelayServerCommand(mainPeer.getRemoteId(),
                mainPeer.getRemoteLogin(),
                mainPeer.isLocalOffer(),
                mainPeer.getCombination()));
    }

    public void disableClient(MainPeer mainPeer) {
        MainPeer oldRelayPeer = mainPeer.getRelayPeer();
        mainPeer.setDisableConnectService(false);
        if (oldRelayPeer != null) {
            oldRelayPeer.getLinkClient().remove(mainPeer.getRemoteId(), mainPeer);
            mainPeer.setRelayPeer(null, false);
            mainPeer.lostConnect();
        }

        mainPeer.getModule(PeerModule.PEER_TO_PEER_SENDER, PeerToPeerSenderModule.class)
                .ifPresent(m -> {
                    if (!m.isEnabled()) {
                        m.enable();
                    }
                });
    }

    @Override
    public void onRelayPeerChange(Peer p, Peer relay) {
        if (peer instanceof MainPeer mainPeer) {

            if (relay == null) {
                log.info("Disable client for {}", p.getPeerIdentifier());
                disableClient(mainPeer);
                return;
            }

            if (relay instanceof MainPeer relayP) {
                enableClient(mainPeer, relayP);
            }
        }
    }

    @Override
    public void onHandleData(Peer r, byte[] data) {
        if (!isEnabled()) {
            return;
        }

        boolean isRelayMsg = data[0] == COMMAND_SERVER || data[0] == COMMAND_CLIENT;

        if (!isRelayMsg) {
            return;
        }

        RelayMessage relayMessage = RelayMessage.fromBytes(data, 1, data.length - 1);
        if (relayMessage == null) {
            log.warn("Relay (Client): message is null from peer {}", r.getPeerIdentifier());
            return;
        }
        if (data[0] == COMMAND_CLIENT && peer instanceof MainPeer mainPeer) {
            fromClientToPeer(mainPeer, relayMessage);
        } else if (data[0] == COMMAND_SERVER && peer instanceof MainPeer mainPeer) {
            fromServerToClient(mainPeer, relayMessage);
        }
    }

    /**
     * Client -> Peer -> Server (here) -> Peer
     */
    private void fromClientToPeer(MainPeer mainPeer, RelayMessage relayMessage) {
        int targetId = relayMessage.targetId();
        byte[] data = relayMessage.data();

        Peer targetPeer = mainPeer.getRelays().get(targetId);

        if (targetPeer == null || targetPeer.isClosing()) {
            log.warn("Relay: message = {}; peer not found or closed {}", relayMessage, mainPeer.getPeerIdentifier());
            return;
        }
        targetPeer.sendToPeer(data);
        log.info("Relay: message = {} from client to peer {}. {}", relayMessage, targetPeer.getPeerIdentifier(), mainPeer.getPeerIdentifier()); //DEBUG
    }

    /**
     * Client <- (here) Peer <- Server <- Peer
     */
    private void fromServerToClient(MainPeer mainPeer, RelayMessage relayMessage) {
        int targetId = relayMessage.targetId();
        byte[] serverData = relayMessage.data();
        Peer clientPeer = mainPeer.getLinkClient().get(targetId);
        if (clientPeer == null) {
            return;
        }
        clientPeer.setLastPacketReceived(System.currentTimeMillis());
        clientPeer.handleData(serverData);
        log.info("Relay: message = {} from server to client {}. {}", relayMessage, clientPeer.getPeerIdentifier(), mainPeer.getPeerIdentifier()); //DEBUG
    }

    private void sendRelayMessage(MainPeer mainPeer, Peer relayPeer, byte[] data) {
        int targetId = mainPeer.getRemoteId();
        RelayMessage relayMessage = new RelayMessage(targetId, data);
        log.warn("(client) send relayMessage to {}. {}", relayPeer.getPeerIdentifier(), relayMessage);
        byte[] messageBytes = relayMessage.toBytes((byte) COMMAND_CLIENT);

        sendRelay(mainPeer, relayPeer, messageBytes);
    }

    private void sendRelay(MainPeer mainPeer, Peer relayPeer, byte[] data) {
        if (relayPeer.isClosing()) {
            log.info("Disable client {}. RelayPeer is closing. Main = {}", relayPeer.getPeerIdentifier(), mainPeer.getPeerIdentifier());
            disableClient(mainPeer);
            return;
        }

        relayPeer.sendToPeer(data);
    }

}
