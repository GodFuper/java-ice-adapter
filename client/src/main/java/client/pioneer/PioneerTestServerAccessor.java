package client.pioneer;

import client.forgedalliance.ForgedAlliance;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.ICEAdapterTest;
import data.ForgedAlliancePeer;
import data.IceStatus;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import logging.Logger;
import lombok.Getter;
import net.*;

/**
 * Connects PioneerTestClient to TestServer to participate in automated test scenarios
 * and bridges ICE signaling between TestServer and Pioneer's MockIcebreaker.
 */
public class PioneerTestServerAccessor {

    private static final Gson gson = new Gson();
    private static Socket socket;
    private static DataInputStream in;
    private static DataOutputStream out;

    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static Queue<Integer> latencies = new LinkedList<>();

    @Getter
    private static PioneerAdapter adapter;
    @Getter
    private static ForgedAlliance forgedAlliance;

    public static void init() {
        running.set(true);

        Thread connector = new Thread(PioneerTestServerAccessor::connectLoop, "Pioneer-TestServer-Connector");
        connector.setDaemon(true);
        connector.start();

        Thread infoThread = new Thread(PioneerTestServerAccessor::infoLoop, "Pioneer-TestServer-Info");
        infoThread.setDaemon(true);
        infoThread.start();
    }

    public static void stop() {
        running.set(false);
        connected.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
        if (adapter != null) {
            adapter.stop();
            adapter = null;
        }
        if (forgedAlliance != null) {
            forgedAlliance.stop();
            forgedAlliance = null;
        }
    }

    private static void connectLoop() {
        while (running.get()) {
            try {
                Logger.info("Connecting to TestServer at %s:%d ...", ICEAdapterTest.TEST_SERVER_ADDRESS, ICEAdapterTest.TEST_SERVER_PORT);
                socket = new Socket(ICEAdapterTest.TEST_SERVER_ADDRESS, ICEAdapterTest.TEST_SERVER_PORT);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                // 1. Server sends version
                int serverVersion = in.readInt();
                if (serverVersion != ICEAdapterTest.VERSION) {
                    Logger.error("TestServer version mismatch: expected %d, got %d", ICEAdapterTest.VERSION, serverVersion);
                    socket.close();
                    return;
                }

                // 2. Client sends username
                out.writeUTF(PioneerTestClient.userName);
                out.flush();

                // 3. Server sends boolean ok
                boolean ok = in.readBoolean();
                if (!ok) {
                    Logger.error("Could not lock in username on TestServer: %s", PioneerTestClient.userName);
                    socket.close();
                    return;
                }

                // 4. Server sends assigned player ID
                int assignedId = in.readInt();
                PioneerTestClient.userId = assignedId;

                // 5. Server sends scenario description
                String scenarioDescription = in.readUTF();

                // 6. Server sends hole punching port
                int holePunchingPort = in.readInt();

                connected.set(true);
                Logger.info("Connected to TestServer as player ID %d (scenario: %s, hpPort: %d)",
                        assignedId, scenarioDescription, holePunchingPort);

                // Start Pioneer adapter with the EXACT assigned player ID
                startAdapterForAssignedUser(assignedId);

                // Start message listener
                listenMessages();

            } catch (Exception e) {
                if (running.get()) {
                    Logger.warning("Connection to TestServer failed: %s. Retrying in 2s...", e.getMessage());
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
            } finally {
                connected.set(false);
            }
        }
    }

    private static void startAdapterForAssignedUser(int assignedId) {
        if (adapter != null) {
            adapter.stop();
        }

        adapter = new PioneerAdapter(
                assignedId,
                PioneerTestClient.userName,
                PioneerTestClient.gameId,
                PioneerTestClient.accessToken,
                PioneerTestClient.apiRoot);

        try {
            adapter.start();
            Logger.info("Started Pioneer adapter for assigned user ID %d (GPGNet: %d)",
                    assignedId, adapter.getGpgNetPort());

            if (adapter.getMockIcebreaker() != null) {
                adapter.getMockIcebreaker().setOnEventListener(PioneerTestServerAccessor::onPioneerIcebreakerEvent);
            }

            if (PioneerGUI.getInstance() != null) {
                Platform.runLater(() -> PioneerGUI.getInstance().bindAdapter(adapter));
            }
        } catch (Exception e) {
            Logger.error("Failed to start Pioneer adapter for assigned user ID " + assignedId, e);
        }
    }

    private static void listenMessages() {
        try {
            while (running.get() && connected.get()) {
                String messageClass = in.readUTF();
                String json = in.readUTF();

                try {
                    Object message = gson.fromJson(json, Class.forName(messageClass));
                    Logger.info("Received TestServer message: %s", messageClass);

                    if (message instanceof HostGameMessage hostMsg) {
                        if (forgedAlliance != null) {
                            forgedAlliance.stop();
                            forgedAlliance = null;
                        }
                        if (adapter != null) {
                            startSimulatedGame();
                            adapter.hostGame(hostMsg.getMapName());
                        }
                    } else if (message instanceof JoinGameMessage joinMsg) {
                        if (forgedAlliance != null) {
                            forgedAlliance.stop();
                            forgedAlliance = null;
                        }
                        if (adapter != null) {
                            startSimulatedGame();
                            adapter.joinGame(
                                    "127.0.0.1:" + adapter.getGpgNetPort(),
                                    joinMsg.getRemotePlayerLogin(),
                                    (int) joinMsg.getRemotePlayerId());
                        }
                    } else if (message instanceof ConnectToPeerMessage connectMsg) {
                        if (adapter != null) {
                            adapter.connectToPeer(
                                    "127.0.0.1:" + adapter.getGpgNetPort(),
                                    connectMsg.getRemotePlayerLogin(),
                                    (int) connectMsg.getRemotePlayerId());
                        }
                    } else if (message instanceof DisconnectFromPeerMessage disconnectMsg) {
                        if (adapter != null) {
                            adapter.disconnectFromPeer((int) disconnectMsg.getRemotePlayerId());
                        }
                    } else if (message instanceof LeaveGameMessage) {
                        if (forgedAlliance != null) {
                            forgedAlliance.stop();
                            forgedAlliance = null;
                        }
                    } else if (message instanceof IceMessage iceMsg) {
                        handleIncomingIceMessage(iceMsg);
                    } else if (message instanceof EchoRequest echoReq) {
                        send(new EchoResponse(echoReq.getTimestamp()));
                    } else if (message instanceof EchoResponse echoRes) {
                        synchronized (latencies) {
                            latencies.add((int) (System.currentTimeMillis() - echoRes.getTimestamp()));
                            if (latencies.size() > 50) {
                                latencies.remove();
                            }
                        }
                    }
                } catch (Exception msgEx) {
                    Logger.warning("Error processing message %s: %s", messageClass, msgEx.getMessage());
                }
            }
        } catch (Exception e) {
            Logger.warning("TestServer listener disconnected: %s", e.getMessage());
        }
    }

    private static void handleIncomingIceMessage(IceMessage iceMsg) {
        if (adapter == null || adapter.getMockIcebreaker() == null) {
            return;
        }

        try {
            Object msgObj = iceMsg.getMsg();
            String rawStr = msgObj instanceof String ? (String) msgObj : gson.toJson(msgObj);
            Logger.info("Pioneer received IceMessage from srcId %d to destId %d: %s",
                    iceMsg.getSrcPlayerId(), iceMsg.getDestPlayerId(), rawStr);

            JsonElement elem = JsonParser.parseString(rawStr);
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                elem = JsonParser.parseString(elem.getAsString());
            }

            JsonObject jsonObject = elem.isJsonObject() ? elem.getAsJsonObject() : new JsonObject();

            JsonObject sseEvent = new JsonObject();
            sseEvent.addProperty("eventType", "candidates");
            sseEvent.addProperty("gameId", PioneerTestClient.gameId);
            sseEvent.addProperty("senderId", iceMsg.getSrcPlayerId());
            sseEvent.addProperty("recipientId", iceMsg.getDestPlayerId());

            if (jsonObject.has("password") && !jsonObject.get("password").isJsonNull()) {
                String sdp = jsonObject.get("password").getAsString();
                String ufrag = jsonObject.has("ufrag") && !jsonObject.get("ufrag").isJsonNull()
                        ? jsonObject.get("ufrag").getAsString()
                        : "offer";

                JsonObject session = new JsonObject();
                session.addProperty("type", ufrag.startsWith("offer") ? "offer" : "answer");
                session.addProperty("sdp", sdp);
                sseEvent.add("session", session);
            } else if (jsonObject.has("session")) {
                sseEvent.add("session", jsonObject.get("session"));
            }

            JsonArray rawCandidates = new JsonArray();
            if (jsonObject.has("candidates") && jsonObject.get("candidates").isJsonArray()) {
                rawCandidates = jsonObject.getAsJsonArray("candidates");
            }

            JsonArray pioneerCandidates = new JsonArray();
            for (JsonElement cElem : rawCandidates) {
                if (!cElem.isJsonObject()) {
                    continue;
                }
                JsonObject src = cElem.getAsJsonObject();
                JsonObject pionCand = new JsonObject();

                // Foundation
                String foundation = src.has("foundation") ? src.get("foundation").getAsString() : "0";
                pionCand.addProperty("foundation", foundation);

                // Priority
                long priority = src.has("priority") ? src.get("priority").getAsLong() : 2130706431L;
                pionCand.addProperty("priority", priority);

                // Address
                String address = "";
                if (src.has("address") && !src.get("address").isJsonNull()) {
                    address = src.get("address").getAsString();
                } else if (src.has("ip") && !src.get("ip").isJsonNull()) {
                    address = src.get("ip").getAsString();
                }
                pionCand.addProperty("address", address);

                // Protocol: 1 = UDP, 2 = TCP in pion/webrtc ICEProtocol
                int protocol = 1;
                if (src.has("protocol")) {
                    JsonElement protoElem = src.get("protocol");
                    if (protoElem.isJsonPrimitive() && protoElem.getAsJsonPrimitive().isNumber()) {
                        protocol = protoElem.getAsInt();
                    } else {
                        String protoStr = protoElem.getAsString().toLowerCase();
                        protocol = protoStr.contains("tcp") ? 2 : 1;
                    }
                }
                pionCand.addProperty("protocol", protocol);

                // Port
                int port = src.has("port") ? src.get("port").getAsInt() : 0;
                pionCand.addProperty("port", port);

                // Type: host, srflx, relay, prflx
                String type = "host";
                if (src.has("type")) {
                    String rawType = src.get("type").getAsString().toLowerCase();
                    if (rawType.contains("srflx") || rawType.contains("server_reflexive")) {
                        type = "srflx";
                    } else if (rawType.contains("relay")) {
                        type = "relay";
                    } else if (rawType.contains("prflx") || rawType.contains("peer_reflexive")) {
                        type = "prflx";
                    } else {
                        type = "host";
                    }
                }
                pionCand.addProperty("type", type);

                // Component
                int component = src.has("component") ? src.get("component").getAsInt() : 1;
                pionCand.addProperty("component", component);

                // RelatedAddress
                String relAddr = "";
                if (src.has("relatedAddress") && !src.get("relatedAddress").isJsonNull()) {
                    relAddr = src.get("relatedAddress").getAsString();
                } else if (src.has("relAddr") && !src.get("relAddr").isJsonNull()) {
                    relAddr = src.get("relAddr").getAsString();
                }
                pionCand.addProperty("relatedAddress", relAddr);

                // RelatedPort
                int relPort = 0;
                if (src.has("relatedPort") && !src.get("relatedPort").isJsonNull()) {
                    relPort = src.get("relatedPort").getAsInt();
                } else if (src.has("relPort") && !src.get("relPort").isJsonNull()) {
                    relPort = src.get("relPort").getAsInt();
                }
                pionCand.addProperty("relatedPort", relPort);

                // tcpType
                pionCand.addProperty("tcpType", src.has("tcpType") ? src.get("tcpType").getAsString() : "");

                // sdpMid & sdpMLineIndex
                pionCand.addProperty("sdpMid", src.has("sdpMid") ? src.get("sdpMid").getAsString() : "0");
                pionCand.addProperty("sdpMLineIndex", src.has("sdpMLineIndex") ? src.get("sdpMLineIndex").getAsInt() : 0);

                pioneerCandidates.add(pionCand);
            }
            sseEvent.add("candidates", pioneerCandidates);

            String sseJson = gson.toJson(sseEvent);
            Logger.info("Dispatching SSE event to Pioneer: %s", sseJson);
            adapter.getMockIcebreaker().sendSseEvent(sseJson);

        } catch (Exception e) {
            Logger.error("Error processing incoming IceMessage for Pioneer", e);
        }
    }

    private static void onPioneerIcebreakerEvent(String eventJson) {
        try {
            JsonObject obj = JsonParser.parseString(eventJson).getAsJsonObject();
            int senderId = obj.has("senderId") ? obj.get("senderId").getAsInt() : PioneerTestClient.userId;
            int recipientId = obj.has("recipientId") && !obj.get("recipientId").isJsonNull()
                    ? obj.get("recipientId").getAsInt()
                    : 0;

            String sdp = "";
            String ufrag = "answer";
            if (obj.has("session") && obj.get("session").isJsonObject()) {
                JsonObject sessionObj = obj.getAsJsonObject("session");
                if (sessionObj.has("sdp")) {
                    sdp = sessionObj.get("sdp").getAsString();
                }
                if (sessionObj.has("type")) {
                    ufrag = sessionObj.get("type").getAsString();
                }
            }

            JsonObject javaCandidatesMsg = new JsonObject();
            javaCandidatesMsg.addProperty("srcId", senderId);
            javaCandidatesMsg.addProperty("destId", recipientId);
            javaCandidatesMsg.addProperty("password", sdp);
            javaCandidatesMsg.addProperty("ufrag", ufrag);
            javaCandidatesMsg.add("candidates", obj.has("candidates") ? obj.get("candidates") : new JsonArray());

            Logger.info("Forwarding Pioneer candidate event to TestServer (src: %d, dest: %d, type: %s)",
                    senderId, recipientId, ufrag);

            send(new IceMessage(senderId, recipientId, gson.toJson(javaCandidatesMsg)));
        } catch (Exception e) {
            Logger.error("Failed to parse and forward Pioneer event", e);
        }
    }

    public static synchronized void send(Object message) {
        if (!connected.get() || out == null) {
            return;
        }
        try {
            String json = gson.toJson(message);
            if (json != null) {
                out.writeUTF(message.getClass().getName());
                out.writeUTF(json);
                out.flush();
            }
        } catch (Exception e) {
            Logger.warning("Failed to send message to TestServer: %s", e.getMessage());
        }
    }

    private static void startSimulatedGame() {
        if (adapter == null) {
            return;
        }
        try {
            Logger.info(
                    "Auto-starting simulated ForgedAlliance for Pioneer (User: %s, ID: %d) on GPGNet port %d and lobby port %d...",
                    PioneerTestClient.userName,
                    PioneerTestClient.userId,
                    adapter.getGpgNetPort(),
                    adapter.getLobbyPort());
            forgedAlliance = new ForgedAlliance(
                    adapter.getGpgNetPort(),
                    adapter.getLobbyPort(),
                    PioneerTestClient.userName,
                    PioneerTestClient.userId);
        } catch (Exception e) {
            Logger.error("Failed to start simulated game in PioneerTestServerAccessor", e);
        }
    }

    private static void infoLoop() {
        while (running.get()) {
            try {
                Thread.sleep(2500);
                if (connected.get()) {
                    ClientInformationMessage info;
                    synchronized (latencies) {
                        List<ForgedAlliancePeer> peersCopy = null;
                        if (forgedAlliance != null && forgedAlliance.getPeers() != null) {
                            synchronized (forgedAlliance.getPeers()) {
                                peersCopy = new ArrayList<>(forgedAlliance.getPeers());
                            }
                        }
                        info = new ClientInformationMessage(
                                PioneerTestClient.userName,
                                PioneerTestClient.userId,
                                System.currentTimeMillis(),
                                new LinkedList<>(latencies),
                                new IceStatus(),
                                Logger.collectedLog != null ? Logger.collectedLog : "",
                                peersCopy);
                        latencies.clear();
                    }
                    send(info);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Logger.warning("Error in Pioneer infoLoop: %s", e.getMessage());
            }
        }
    }
}
