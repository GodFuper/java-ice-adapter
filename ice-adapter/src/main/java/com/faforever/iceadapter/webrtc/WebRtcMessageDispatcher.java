package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Receives JSON messages from WebRTC data channel and routes them to the same handlers
 * as RPCHandler does for TCP JSON-RPC.
 */
@Slf4j
public class WebRtcMessageDispatcher {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final FafRpcCallbacks callbacks;

    public WebRtcMessageDispatcher(FafRpcCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * Process an incoming message from the data channel.
     * Messages are JSON-RPC style: {"method": "...", "params": [...]}
     * Or direct method calls for backward compatibility.
     */
    public void handleMessage(byte[] data, boolean isBinary) {
        if (isBinary) {
            log.debug("Received binary data from data channel: {} bytes", data.length);
            // Binary data could be ICE candidates or raw data
            // For now, log and ignore binary data from data channel
            // (ICE candidates come through signaling, not data channel)
            return;
        }

        String message;
        try {
            message = new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decode message from data channel", e);
            return;
        }

        log.debug("Received message from data channel: {}", message);

        try {
            // Try to parse as JSON-RPC
            parseAndRouteJsonRpc(message);
        } catch (Exception e) {
            log.error("Failed to process message from data channel: {}", message, e);
        }
    }

    private void parseAndRouteJsonRpc(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.has("method")) {
                log.warn("Could not extract method from JSON: {}", json);
                return;
            }
            String method = root.get("method").asText();
            JsonNode params = root.get("params");
            log.info("Routing data channel message to method: {}", method);

            switch (method) {
                case "hostGame" -> {
                    if (params != null && params.size() > 0) {
                        callbacks.onHostGame(params.get(0).asText());
                    }
                }
                case "joinGame" -> {
                    if (params != null && params.size() > 1) {
                        callbacks.onJoinGame(
                                params.get(0).asText(), params.get(1).asInt());
                    }
                }
                case "connectToPeer" -> {
                    if (params != null && params.size() > 2) {
                        callbacks.onConnectToPeer(
                                params.get(0).asText(),
                                params.get(1).asInt(),
                                params.get(2).asBoolean());
                    }
                }
                case "disconnectFromPeer" -> {
                    if (params != null && params.size() > 0) {
                        callbacks.onDisconnectFromPeer(params.get(0).asInt());
                    }
                }
                case "iceMsg" -> {
                    if (params != null && params.size() > 1) {
                        processIceMsg(params.get(0).asInt(), params.get(1).asText());
                    }
                }
                case "sendToGpgNet" -> {
                    if (params != null && params.size() > 0) {
                        String header = params.get(0).asText();
                        List<Object> args = new ArrayList<>();
                        for (int i = 1; i < params.size(); i++) {
                            args.add(params.get(i).asText());
                        }
                        callbacks.sendToGpgNet(header, args.toArray());
                    }
                }
                case "setIceServers" -> {
                    if (params != null && params.size() > 0) {
                        List<Map<String, Object>> iceServers = objectMapper.convertValue(
                                params.get(0),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                        GameSession.setIceServers(iceServers);
                    }
                }
                case "quit" -> callbacks.close();
                default -> log.warn("Unknown method from data channel: {}", method);
            }
        } catch (Exception e) {
            log.error("Failed to process message from data channel: {}", json, e);
        }
    }

    private void processIceMsg(int remotePlayerId, String msgJson) {
        log.info("iceMsg received from data channel for peer {}", remotePlayerId);
        CandidatesMessage message;
        try {
            message = objectMapper.readValue(msgJson, CandidatesMessage.class);
        } catch (Exception e) {
            log.error("Failed to parse iceMsg from data channel: {}", msgJson, e);
            return;
        }

        int idFrom = message.srcId();
        int idTo = message.destId();
        int myId = IceAdapter.getId();
        if (myId != idTo) {
            log.error("The iceMsg {} is not meant for {}. Ignored", message, idTo);
            return;
        }

        if (remotePlayerId != idFrom) {
            log.error("The sender {} != {} does not match the IceMsg source. Ignored", remotePlayerId, idFrom);
            return;
        }

        GameSession gameSession = IceAdapter.getGameSessionSafe();
        if (gameSession == null) {
            log.error("Game session is null. IceMsg ignored. {}", message);
            return;
        }

        Peer peer = gameSession.getPeers().get(remotePlayerId);
        if (peer == null) {
            log.error("Peer not found for id: {}. IceMsg ignored. {}", remotePlayerId, message);
            return;
        }

        // Route to peer - same as RPCHandler.iceMsg()
        peer.iceMessageFromRPC(message);
    }
}
