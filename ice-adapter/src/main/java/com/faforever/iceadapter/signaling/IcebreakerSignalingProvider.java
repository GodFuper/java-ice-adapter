package com.faforever.iceadapter.signaling;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.icebreaker.IcebreakerHttpClient;
import com.faforever.iceadapter.icebreaker.IcebreakerMessageConverter;
import com.faforever.iceadapter.icebreaker.IcebreakerSseListener;
import com.faforever.iceadapter.icebreaker.dto.EventMessageDto;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IcebreakerSignalingProvider implements SignalingProvider, Consumer<EventMessageDto> {

    @Getter
    private final long gameId;

    @Getter
    private final int localPlayerId;

    @Getter
    private final IcebreakerHttpClient httpClient;

    @Getter
    private final IcebreakerSseListener sseListener;

    private final Supplier<GameSession> gameSessionSupplier;
    private final Set<Integer> closingPeers = ConcurrentHashMap.newKeySet();

    public IcebreakerSignalingProvider(
            long gameId,
            int localPlayerId,
            IcebreakerHttpClient httpClient,
            IcebreakerSseListener sseListener,
            Supplier<GameSession> gameSessionSupplier) {
        this.gameId = gameId;
        this.localPlayerId = localPlayerId;
        this.httpClient = httpClient;
        this.sseListener = sseListener;
        this.gameSessionSupplier = gameSessionSupplier;
        if (sseListener != null) {
            sseListener.setOnEvent(this);
        }
    }

    public IcebreakerSignalingProvider(
            long gameId,
            int localPlayerId,
            IcebreakerHttpClient httpClient,
            IcebreakerSseListener sseListener,
            GameSession gameSession) {
        this(gameId, localPlayerId, httpClient, sseListener, () -> gameSession);
    }

    @Override
    public void init() {
        log.info("Initializing IcebreakerSignalingProvider for game {}", gameId);
        if (sseListener != null) {
            sseListener.start();
        }
    }

    @Override
    public void sendSignalingMessage(CandidatesMessage message) {
        log.debug("Sending CandidatesMessage via icebreaker: src={}, dest={}", message.srcId(), message.destId());
        EventMessageDto.Candidates event = IcebreakerMessageConverter.toIcebreakerCandidates(gameId, message);
        if (httpClient != null) {
            httpClient.sendEvent(gameId, event).exceptionally(throwable -> {
                log.error("Failed to send CandidatesMessage via icebreaker", throwable);
                return null;
            });
        }
    }

    @Override
    public void onPeerDisconnected(int peerId) {
        if (closingPeers.contains(peerId)) {
            return;
        }
        log.info("Sending PeerClosing for peer {} in game {}", peerId, gameId);
        EventMessageDto.PeerClosing event = new EventMessageDto.PeerClosing(gameId, localPlayerId, (long) peerId);
        if (httpClient != null) {
            httpClient.sendEvent(gameId, event).exceptionally(throwable -> {
                log.error("Failed to send PeerClosing event for peer {}", peerId, throwable);
                return null;
            });
        }
    }

    @Override
    public void close() {
        log.info("Closing IcebreakerSignalingProvider for game {}", gameId);
        EventMessageDto.PeerClosing event = new EventMessageDto.PeerClosing(gameId, localPlayerId, null);
        if (httpClient != null) {
            try {
                httpClient.sendEvent(gameId, event).exceptionally(throwable -> {
                    log.error("Failed to send final PeerClosing event", throwable);
                    return null;
                });
            } catch (Exception e) {
                log.warn("Failed to send closing event on close", e);
            }
        }
        if (sseListener != null) {
            sseListener.close();
        }
    }

    @Override
    public void accept(EventMessageDto event) {
        handleIncomingEvent(event);
    }

    public void handleIncomingEvent(EventMessageDto event) {
        if (event == null) {
            return;
        }

        if (event.senderId() == localPlayerId) {
            log.trace("Ignoring event from self: {}", event);
            return;
        }

        if (event.recipientId() != null && event.recipientId() != (long) localPlayerId) {
            log.trace("Ignoring event addressed to other recipient: {}", event);
            return;
        }

        switch (event) {
            case EventMessageDto.Connected connected -> handleConnected(connected);
            case EventMessageDto.Candidates candidates -> handleCandidates(candidates);
            case EventMessageDto.PeerClosing closing -> handlePeerClosing(closing);
            default -> log.debug("Unhandled event type: {}", event);
        }
    }

    private void handleConnected(EventMessageDto.Connected connected) {
        int remotePlayerId = (int) connected.senderId();
        GameSession gameSession = getGameSession();
        if (gameSession == null) {
            log.warn("Cannot handle Connected event: GameSession is null");
            return;
        }

        if (!gameSession.getPeers().containsKey(remotePlayerId) && localPlayerId < remotePlayerId) {
            log.info("Initiating connection to peer {} as offerer", remotePlayerId);
            AllowCombination combination = determineCombination(gameSession);
            gameSession.connectToPeer(String.valueOf(remotePlayerId), remotePlayerId, true, 0, combination);
        }
    }

    private void handleCandidates(EventMessageDto.Candidates candidates) {
        CandidatesMessage candidatesMessage = IcebreakerMessageConverter.fromIcebreakerCandidates(candidates);
        GameSession gameSession = getGameSession();
        if (gameSession == null) {
            log.warn("Cannot handle Candidates event: GameSession is null");
            return;
        }

        int remotePlayerId = candidatesMessage.srcId();
        Peer peer = gameSession.getPeers().get(remotePlayerId);
        if (peer == null) {
            boolean isOfferer = localPlayerId < remotePlayerId;
            log.info("Creating peer {} on receiving candidates (isOfferer={})", remotePlayerId, isOfferer);
            AllowCombination combination = determineCombination(gameSession);
            gameSession.connectToPeer(String.valueOf(remotePlayerId), remotePlayerId, isOfferer, 0, combination);
            peer = gameSession.getPeers().get(remotePlayerId);
        }

        if (peer != null) {
            peer.iceMessageFromRPC(candidatesMessage);
        } else {
            log.error("Failed to find or create peer {} to deliver candidates", remotePlayerId);
        }
    }

    private void handlePeerClosing(EventMessageDto.PeerClosing closing) {
        int remotePlayerId = (int) closing.senderId();
        log.info("Handling PeerClosing from peer {}", remotePlayerId);
        GameSession gameSession = getGameSession();
        if (gameSession != null) {
            closingPeers.add(remotePlayerId);
            try {
                gameSession.disconnectFromPeer(remotePlayerId);
            } finally {
                closingPeers.remove(remotePlayerId);
            }
        }
    }

    private GameSession getGameSession() {
        return gameSessionSupplier != null ? gameSessionSupplier.get() : null;
    }

    private AllowCombination determineCombination(GameSession gameSession) {
        return (gameSession.getOptions() != null && gameSession.getOptions().isForceRelay())
                ? AllowCombination.RELAY
                : AllowCombination.ALL;
    }
}
