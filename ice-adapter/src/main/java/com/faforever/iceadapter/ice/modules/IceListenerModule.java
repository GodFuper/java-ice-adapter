package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.*;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.IceUtils;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;

import java.net.DatagramPacket;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.faforever.iceadapter.util.DatagramSocketUtils.MAX_SIZE_PACKET;

@Slf4j
@RequiredArgsConstructor
public class IceListenerModule implements ModuleBase {
    private static final String LOCK_MODULE = "IceListenerModule";

    private final IceGameSession iceGameSession;
    private final IcePeerAdapter icePeerAdapter;
    private final Peer peer;

    private final Set<CompletableFuture<Void>> listeners = new HashSet<>();

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::startListeners);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopListeners);
    }

    private void startListeners() {
        Optional.ofNullable(peer.getMediaStream()).ifPresent(mediaStream -> {
            for (Component component : mediaStream.getComponents()) {
                listeners.add(CompletableFuture.runAsync(listener(component), ExecutorHolder.getExecutor()));
            }
        });
    }

    private void stopListeners() {
        for (CompletableFuture<Void> listener : listeners) {
            listener.cancel(true);
        }
        listeners.clear();
    }

    private String getThreadName() {
        return "%s-IceListenerModule-%s".formatted(Thread.currentThread().getName(), peer.getPeerIdentifier());
    }

    /**
     * Listens for data incoming via ice socket
     */
    public Runnable listener(Component component) {
        Optional<ConnectivityModule> connectivityModule = peer.getModule(IceModule.ICE_LISTENER_MODULE, ConnectivityModule.class);
        return () -> {
            Thread.currentThread().setName(getThreadName());
            log.debug("Now forwarding data from ICE to FA for peer");

            byte[] data = new byte[MAX_SIZE_PACKET];
            while (!iceGameSession.isGameEnded() || !peer.isClosing()) {
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    component.getSocket().receive(packet);

                    connectivityModule.ifPresent(ConnectivityModule::onReceivePacket);
                    if (packet.getLength() == 0) {
                        continue;
                    }

                    if (data[0] == 'd') {
                        icePeerAdapter.onIceDataReceived(peer, data, 1, packet.getLength() - 1);
                    } else if (data[0] == ConnectivityModule.COMMAND_ECHO) {
                        connectivityModule.ifPresent(module -> module.onEchoReceived(data, packet.getLength()));
                    } else {
                        log.warn("Received invalid packet, first byte: 0x{}, length: {}", data[0], packet.getLength());
                    }

                } catch (Exception e) {
                    log.warn("Error while reading from ICE adapter", e);
                    break;
                }

            }

            Optional<Component> activeComponent = IceUtils.getFirstActiveComponent(peer);
            if (activeComponent.isEmpty()) {
                icePeerAdapter.onConnectionLost(peer);
            }

            log.debug("No longer listening for messages from ICE");
        };
    }

}
