package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
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
        // Parse JSON to extract method and params
        // Format: {"method": "iceMsg", "params": [remotePlayerId, msg]}
        // Or simpler: {"method": "hostGame", "params": [mapName]}

        // Use a simple approach: parse the JSON manually for method name
        String method = extractMethod(json);
        if (method == null) {
            log.warn("Could not extract method from JSON: {}", json);
            return;
        }

        log.info("Routing data channel message to method: {}", method);

        switch (method) {
            case "hostGame" -> {
                String mapName = extractParamString(json, 0);
                if (mapName != null) {
                    callbacks.onHostGame(mapName);
                }
            }
            case "joinGame" -> {
                String remotePlayerLogin = extractParamString(json, 0);
                Long remotePlayerId = extractParamLong(json, 1);
                if (remotePlayerLogin != null && remotePlayerId != null) {
                    callbacks.onJoinGame(remotePlayerLogin, remotePlayerId.intValue());
                }
            }
            case "connectToPeer" -> {
                String remotePlayerLogin = extractParamString(json, 0);
                Long remotePlayerId = extractParamLong(json, 1);
                Boolean offer = extractParamBoolean(json, 2);
                if (remotePlayerLogin != null && remotePlayerId != null && offer != null) {
                    callbacks.onConnectToPeer(remotePlayerLogin, remotePlayerId.intValue(), offer);
                }
            }
            case "disconnectFromPeer" -> {
                Long remotePlayerId = extractParamLong(json, 0);
                if (remotePlayerId != null) {
                    callbacks.onDisconnectFromPeer(remotePlayerId.intValue());
                }
            }
            case "iceMsg" -> {
                Long remotePlayerId = extractParamLong(json, 0);
                String msgJson = extractParamString(json, 1);
                if (remotePlayerId != null && msgJson != null) {
                    processIceMsg(remotePlayerId.intValue(), msgJson);
                }
            }
            case "sendToGpgNet" -> {
                String header = extractParamString(json, 0);
                // Extract remaining params as varargs
                String[] args = extractParamsArray(json, 1);
                if (header != null) {
                    callbacks.sendToGpgNet(header, (Object[]) args);
                }
            }
            case "setIceServers" -> {
                List<Map<String, Object>> iceServers = extractParamList(json, 0);
                if (iceServers != null) {
                    GameSession.setIceServers(iceServers);
                }
            }
            case "quit" -> {
                callbacks.close();
            }
            default -> log.warn("Unknown method from data channel: {}", method);
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

    // ==================== JSON Parsing Helpers ====================

    /**
     * Extract method name from JSON-RPC message.
     */
    private String extractMethod(String json) {
        int methodIdx = json.indexOf("\"method\"");
        if (methodIdx == -1) {
            return null;
        }
        int colonIdx = json.indexOf(":", methodIdx);
        int quoteStart = json.indexOf("\"", colonIdx + 1);
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteStart == -1 || quoteEnd == -1) {
            return null;
        }
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Extract a string parameter by index from JSON-RPC params array.
     */
    private String extractParamString(String json, int index) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx == -1) {
            // Try positional params without "params" key
            return extractPositionalString(json, index);
        }

        int arrayStart = json.indexOf("[", paramsIdx);
        if (arrayStart == -1) {
            return null;
        }

        // Find the Nth element
        int currentIdx = 0;
        int pos = arrayStart + 1;
        while (pos < json.length() && currentIdx <= index) {
            pos = json.indexOf("\"", pos);
            if (pos == -1 || pos >= json.length()) {
                break;
            }
            int quoteEnd = json.indexOf("\"", pos + 1);
            if (quoteEnd == -1) {
                break;
            }
            if (currentIdx == index) {
                return json.substring(pos + 1, quoteEnd);
            }
            currentIdx++;
            pos = quoteEnd + 1;
        }
        return null;
    }

    /**
     * Extract a long parameter by index.
     */
    private Long extractParamLong(String json, int index) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx == -1) {
            return extractPositionalLong(json, index);
        }

        int arrayStart = json.indexOf("[", paramsIdx);
        if (arrayStart == -1) {
            return null;
        }

        int currentIdx = 0;
        int pos = arrayStart + 1;
        while (pos < json.length() && currentIdx <= index) {
            // Skip whitespace and commas
            while (pos < json.length() && " \t\n\r,".indexOf(json.charAt(pos)) != -1) {
                pos++;
            }
            if (pos >= json.length() || json.charAt(pos) == ']') {
                break;
            }
            if (currentIdx == index) {
                int numStart = pos;
                boolean negative = json.charAt(pos) == '-';
                if (negative) pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
                    pos++;
                }
                try {
                    return Long.parseLong(json.substring(numStart, pos));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            currentIdx++;
            pos++;
        }
        return null;
    }

    /**
     * Extract a boolean parameter by index.
     */
    private Boolean extractParamBoolean(String json, int index) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx == -1) {
            return extractPositionalBoolean(json, index);
        }

        int arrayStart = json.indexOf("[", paramsIdx);
        if (arrayStart == -1) {
            return null;
        }

        int currentIdx = 0;
        int pos = arrayStart + 1;
        while (pos < json.length() && currentIdx <= index) {
            while (pos < json.length() && " \t\n\r,".indexOf(json.charAt(pos)) != -1) {
                pos++;
            }
            if (pos >= json.length() || json.charAt(pos) == ']') {
                break;
            }
            if (currentIdx == index) {
                if (json.startsWith("true", pos)) {
                    return true;
                } else if (json.startsWith("false", pos)) {
                    return false;
                }
                return null;
            }
            currentIdx++;
            // Skip to next comma
            while (pos < json.length() && json.charAt(pos) != ',') {
                pos++;
            }
            pos++;
        }
        return null;
    }

    /**
     * Extract a list parameter by index.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractParamList(String json, int index) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx == -1) {
            return null;
        }

        int arrayStart = json.indexOf("[", paramsIdx);
        if (arrayStart == -1) {
            return null;
        }

        int currentIdx = 0;
        int pos = arrayStart + 1;
        while (pos < json.length() && currentIdx <= index) {
            while (pos < json.length() && " \t\n\r,".indexOf(json.charAt(pos)) != -1) {
                pos++;
            }
            if (pos >= json.length() || json.charAt(pos) == ']') {
                break;
            }
            if (currentIdx == index && json.charAt(pos) == '[') {
                // Found the array - parse it with Jackson
                int arrayEnd = findMatchingBracket(json, pos);
                if (arrayEnd == -1) {
                    return null;
                }
                try {
                    return objectMapper.readValue(
                            json.substring(pos, arrayEnd + 1),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                    );
                } catch (Exception e) {
                    log.error("Failed to parse iceServers list", e);
                    return null;
                }
            }
            currentIdx++;
            while (pos < json.length() && json.charAt(pos) != ',') {
                pos++;
            }
            pos++;
        }
        return null;
    }

    /**
     * Extract varargs parameters as String array.
     */
    private String[] extractParamsArray(String json, int startIndex) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx == -1) {
            return new String[0];
        }

        int arrayStart = json.indexOf("[", paramsIdx);
        if (arrayStart == -1) {
            return new String[0];
        }

        java.util.List<String> result = new java.util.ArrayList<>();
        int currentIdx = 0;
        int pos = arrayStart + 1;
        while (pos < json.length()) {
            while (pos < json.length() && " \t\n\r,".indexOf(json.charAt(pos)) != -1) {
                pos++;
            }
            if (pos >= json.length() || json.charAt(pos) == ']') {
                break;
            }
            if (currentIdx >= startIndex && json.charAt(pos) == '"') {
                int quoteEnd = json.indexOf("\"", pos + 1);
                if (quoteEnd != -1) {
                    result.add(json.substring(pos + 1, quoteEnd));
                }
            }
            currentIdx++;
            while (pos < json.length() && json.charAt(pos) != ',') {
                pos++;
            }
            pos++;
        }
        return result.toArray(new String[0]);
    }

    // ==================== Positional Param Extraction (no "params" key) ====================

    private String extractPositionalString(String json, int index) {
        int pos = findNthString(json, index);
        return pos == -1 ? null : json.substring(pos + 1, json.indexOf("\"", pos + 1));
    }

    private Long extractPositionalLong(String json, int index) {
        int pos = findNthValue(json, index);
        if (pos == -1) return null;
        int end = pos;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Long.parseLong(json.substring(pos, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean extractPositionalBoolean(String json, int index) {
        int pos = findNthValue(json, index);
        if (pos == -1) return null;
        if (json.startsWith("true", pos)) return true;
        if (json.startsWith("false", pos)) return false;
        return null;
    }

    private int findNthString(String json, int n) {
        int pos = 0;
        for (int i = 0; i <= n; i++) {
            pos = json.indexOf("\"", pos);
            if (pos == -1) return -1;
            pos++;
        }
        return pos - 1;
    }

    private int findNthValue(String json, int n) {
        int pos = 0;
        int bracketCount = 0;
        int arrayDepth = 0;
        boolean inArray = false;

        // Find params array first
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx != -1) {
            pos = json.indexOf("[", paramsIdx);
            if (pos != -1) {
                inArray = true;
                arrayDepth = 1;
                pos++;
            }
        }

        if (!inArray) {
            // Try to find first array in the JSON
            pos = json.indexOf("[");
            if (pos != -1) {
                inArray = true;
                arrayDepth = 1;
                pos++;
            }
        }

        if (!inArray) return -1;

        int currentIdx = 0;
        while (pos < json.length() && arrayDepth > 0) {
            char c = json.charAt(pos);
            if (c == '[') arrayDepth++;
            else if (c == ']') arrayDepth--;
            else if (arrayDepth == 1 && c != ',' && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                if (currentIdx == n) {
                    return pos;
                }
                // Skip this value
                while (pos < json.length() && json.charAt(pos) != ',' && json.charAt(pos) != ']') {
                    pos++;
                }
                currentIdx++;
                continue;
            }
            pos++;
        }
        return -1;
    }

    private int findMatchingBracket(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            else if (json.charAt(i) == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
