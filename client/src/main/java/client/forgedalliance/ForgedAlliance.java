package client.forgedalliance;

import client.GUI;
import client.TestClient;
import common.ICEAdapterTest;
import data.ForgedAlliancePeer;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import javafx.geometry.Insets;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import logging.Logger;
import lombok.Getter;

@Getter
public class ForgedAlliance {

	private static final int ECHO_INTERVAL = 100;
	private static final int CONNECTION_REQ_INTERVAL = 1000;

	private static final String CONNECTION_ACK = "conAck";
	private static final String CONNECTION_REQ = "conReq";
	private static final String ECHO_REQ = "echoReq";
	private static final String ECHO_RES = "echoRes";

	private final Random random = new Random();

	private boolean running = true;

	private final int gpgnetPort; // gpgnet to connect to
	private final int lobbyPort; // incoming messages
	private final String localUsername;
	private final int localPlayerId;

	private DatagramSocket lobbySocket;

	private Socket gpgnetSocket;
	private FaDataInputStream gpgnetIn;
	private FaDataOutputStream gpgnetOut;

	public List<ForgedAlliancePeer> peers = new ArrayList<>();

	public ForgedAlliance(int gpgnetPort, int lobbyPort) {
		this(gpgnetPort, lobbyPort, TestClient.username, TestClient.playerID);
	}

	public ForgedAlliance(int gpgnetPort, int lobbyPort, String localUsername, int localPlayerId) {
		this.gpgnetPort = gpgnetPort;
		this.lobbyPort = lobbyPort;
		this.localUsername = localUsername != null ? localUsername : "Player";
		this.localPlayerId = localPlayerId;

		try {
			gpgnetSocket = new Socket("localhost", gpgnetPort);
			lobbySocket = new DatagramSocket(lobbyPort);

			gpgnetIn = new FaDataInputStream(gpgnetSocket.getInputStream());
			gpgnetOut = new FaDataOutputStream(gpgnetSocket.getOutputStream());

			new Thread(this::gpgpnetListener).start();
			new Thread(this::lobbyListener).start();

			new Thread(this::echoThread).start();

			synchronized (gpgnetOut) {
				gpgnetOut.writeMessage("GameState", "Idle");
			}

		} catch(IOException e) {
			Logger.error("Could not start lobby server. (GPGNET or lobbySocket failed)", e);
		}
	}

	private void echoThread() {
		while(running) {

			if(System.currentTimeMillis() - lastMetricsUpdate >= 1000) {
				updateMetrics();
			}

			synchronized (peers) {
				peers.stream()
						.filter(ForgedAlliancePeer::isConnected)
						.forEach(peer -> {
							try {
								ByteArrayOutputStream data = new ByteArrayOutputStream();
								DataOutputStream packetOut = new DataOutputStream(data);

								packetOut.writeUTF(ECHO_REQ);
								packetOut.writeInt(this.localPlayerId);//src
								packetOut.writeInt(peer.remoteId);//target
								packetOut.writeInt(peer.echoRequestsSent++);
								packetOut.writeLong(System.currentTimeMillis());
								int randomBytes;
								if(ICEAdapterTest.TEST_DATA_RANDOM_SIZE) {
									randomBytes = 25 + random.nextInt(50);
								} else {
									randomBytes = 60;//FA sends 2 packets per tick, one ~15 bytes, one 30-70 bytes
								}
								byte[] randomData = new byte[randomBytes];
								random.nextBytes(randomData);
								packetOut.writeInt(randomBytes);
								packetOut.write(randomData, 0, randomData.length);

								sendLobby(peer.remoteAddress, peer.remotePort, data);
							} catch(IOException e) {
								Logger.warning("Error while sending to peer: %d", peer.remoteId);
							}
						});

				peers.stream()
						.filter(p -> !p.isConnected())
						.forEach(peer -> {
							try {
								if ((System.currentTimeMillis() - peer.lastConnectionRequestSent) >= CONNECTION_REQ_INTERVAL) {
									peer.lastConnectionRequestSent = System.currentTimeMillis();

									ByteArrayOutputStream data = new ByteArrayOutputStream();
									DataOutputStream packetOut = new DataOutputStream(data);

									packetOut.writeUTF(CONNECTION_REQ);
									packetOut.writeInt(this.localPlayerId);
									packetOut.writeUTF(this.localUsername);

									sendLobby(peer.remoteAddress, peer.remotePort, data);

									Logger.debug("<FA> Sent CONNECTION_REQ to %s(%d) at %s:%d", peer.remoteUsername, peer.remoteId, peer.remoteAddress, peer.remotePort);
								}
							} catch(IOException e) {
								Logger.warning("Error while sending to peer: %d", peer.remoteId);
							}
						});

				//Data
				peers.stream()
						.filter(p -> System.currentTimeMillis() - p.lastPacketReceived > 10000)
						.forEach(p -> p.addLatency((int)(System.currentTimeMillis() - p.lastPacketReceived)));
			}

			try { Thread.sleep(ECHO_INTERVAL); } catch(InterruptedException e) {}
		}
	}

	private long lastMetricsUpdate = System.currentTimeMillis();
	public float bytesPerSecondIn = 0;
	public float bytesPerSecondOut = 0;
	private void updateMetrics() {
		int d = (int)(System.currentTimeMillis() - lastMetricsUpdate);
		lastMetricsUpdate += d;

		bytesPerSecondIn = (float)bytesReceived.getAndSet(0) / ((float)d / 1000f);
		bytesPerSecondOut = (float)bytesSent.getAndSet(0) / ((float)d / 1000f);
	}

	private volatile AtomicLong bytesReceived = new AtomicLong(0);
	private void lobbyListener() {
		try {
			byte[] buffer = new byte[4096];
			while(running) {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				lobbySocket.receive(packet);

				bytesReceived.addAndGet(packet.getLength());

				DataInputStream packetIn = new DataInputStream(new ByteArrayInputStream(packet.getData()));
				String command = packetIn.readUTF();

				if(command.equals(CONNECTION_REQ)) {
					int remoteId = packetIn.readInt();
					String remoteUsername = packetIn.readUTF();

					ForgedAlliancePeer peer;
					synchronized (peers) {
						if(peers.stream().noneMatch(p -> p.remoteId == remoteId)) {
							peer = new ForgedAlliancePeer(packet.getAddress().getHostAddress(), packet.getPort(), remoteId, remoteUsername, ForgedAlliancePeer.Offerer.REMOTE);
							peers.add(peer);
						} else {
							peer = peers.stream().filter(p -> p.remoteId == remoteId).findAny().get();
						}
						peer.setConnected(true);
						peer.lastPacketReceived = System.currentTimeMillis();
					}

					ByteArrayOutputStream data = new ByteArrayOutputStream();
					DataOutputStream packetOut = new DataOutputStream(data);

					packetOut.writeUTF(CONNECTION_ACK);
					packetOut.writeInt(this.localPlayerId);
					packetOut.writeUTF(this.localUsername);

					sendLobby(peer.remoteAddress, peer.remotePort, data);

					Logger.debug("<FA> Sent CONNECTION_ACK to %s(%d) at %s:%d", peer.remoteUsername, peer.remoteId, peer.remoteAddress, peer.remotePort);
				}

				if(command.equals(CONNECTION_ACK)) {
					int remoteId = packetIn.readInt();
					String remoteUsername = packetIn.readUTF();

					synchronized (peers) {
						peers.stream().filter(p -> p.remoteId == remoteId).findAny().ifPresent(p -> {
							p.setConnected(true);
							p.lastPacketReceived = System.currentTimeMillis();
						});
					}

					Logger.debug("<FA> Got CONNECTION_ACK from %s(%d) at %s:%d", remoteUsername, remoteId, packet.getAddress().getHostAddress(), packet.getPort());
				}

				if(command.equals(ECHO_REQ)) {
					int remoteId = packetIn.readInt();
					int localId = packetIn.readInt();
					int echoReqId = packetIn.readInt();
					long echoReqTime = packetIn.readLong();
					int randomBytes = packetIn.readInt();
					byte[] randomData = new byte[randomBytes];
					packetIn.read(randomData, 0, randomBytes);

					synchronized (peers) {
						peers.stream().filter(p -> p.remoteId == remoteId).findAny().ifPresent(p -> p.lastPacketReceived = System.currentTimeMillis());
					}

					//Construct response
					ByteArrayOutputStream data = new ByteArrayOutputStream();
					DataOutputStream packetOut = new DataOutputStream(data);

					packetOut.writeUTF(ECHO_RES);
					packetOut.writeInt(this.localPlayerId);//src
					packetOut.writeInt(remoteId);//target
					packetOut.writeInt(echoReqId);
					packetOut.writeLong(echoReqTime);
					packetOut.writeInt(randomBytes);
					packetOut.write(randomData, 0, randomData.length);

					sendLobby(packet.getAddress().getHostAddress(), packet.getPort(), data);
				}

				if(command.equals(ECHO_RES)) {
					int remoteId = packetIn.readInt();
					int localId = packetIn.readInt();
					int echoReqId = packetIn.readInt();
					long echoReqTime = packetIn.readLong();
					int randomBytes = packetIn.readInt();
					byte[] randomData = new byte[randomBytes];
					packetIn.read(randomData, 0, randomBytes);

					int latency = (int) (System.currentTimeMillis() - echoReqTime);

					if(echoReqId > 5 && latency < 2000) {
						synchronized (peers) {
							peers.stream().filter(p -> p.remoteId == remoteId).findAny().ifPresent(p -> p.addLatency(latency));
						}
					}
				}
			}
		} catch(IOException e) {
			if(this.running && lobbySocket != null && !lobbySocket.isClosed()) {
				Logger.error("Error while listening for lobby messages.", e);
			}
		}
	}

	private void gpgpnetListener() {
		try {
			while(running) {

				String command = gpgnetIn.readString();
				List<Object> args = gpgnetIn.readChunks();

				if(command.equals("CreateLobby")) {
					try {
						int newPort = (Integer) args.get(1);
						if (lobbySocket == null || lobbySocket.getLocalPort() != newPort) {
							if (lobbySocket != null) {
								lobbySocket.close();
							}
							lobbySocket = new DatagramSocket(newPort);
							new Thread(this::lobbyListener).start();
						}
						Logger.info("<GPG> Creating lobby on port %d", newPort);
					} catch (Exception e) {
						Logger.error("<GPG> Failed to bind lobby socket on CreateLobby port", e);
					}
					synchronized (gpgnetOut) {
						gpgnetOut.writeMessage("GameState", "Lobby");
					}
				}

				if(command.equals("HostGame")) {
					Logger.info("<GPG> Hosting game on %s", args.get(0));
				}

				if(command.equals("JoinGame")) {
					String hostAddr = (String) args.get(0);
					String hostName = (String) args.get(1);
					int hostId = (Integer) args.get(2);
					String[] parts = hostAddr.split(":");
					ForgedAlliancePeer peer = new ForgedAlliancePeer(parts[0], Integer.parseInt(parts[1]), hostId, hostName, ForgedAlliancePeer.Offerer.REMOTE);

					synchronized (peers) {
						if(peers.stream().noneMatch(p -> p.remoteId == peer.remoteId)) {
							peers.add(peer);
						}
					}
					Logger.info("<GPG> Joined game hosted by %s(%d) at %s", hostName, hostId, hostAddr);
				}

				if(command.equals("ConnectToPeer")) {
					String[] parts = ((String)args.get(0)).split(":");
					ForgedAlliancePeer peer = new ForgedAlliancePeer(parts[0], Integer.parseInt(parts[1]), (Integer) args.get(2), (String)args.get(1), ForgedAlliancePeer.Offerer.LOCAL);

					synchronized (peers) {
						if(peers.stream().noneMatch(p -> p.remoteId == peer.remoteId)) {
							peers.add(peer);
						}
					}
					Logger.info("<GPG> Connected to peer %s(%d) at %s", args.get(1), args.get(2), args.get(0));
				}

				if(command.equals("DisconnectFromPeer")) {
					synchronized (peers) {
						peers.stream().filter(p -> p.remoteId == (Integer) args.get(0)).findAny()
								.ifPresent(p -> {
									p.setConnected(false);
									peers.remove(p);
								});
					}
				}

				Logger.debug("<GPG> Received %s %s", command, args.stream().reduce("", (l, r) -> l + " " + r));
			}
		} catch(IOException e) {
			if(this.running) {
				Logger.error("Error while listening for gpg messages.", e);
				if (GUI.instance != null && GUI.instance.getRoot() != null) {
					GUI.runAndWait(() -> GUI.instance.getRoot().setBackground(new Background(new BackgroundFill(new Color(189.0 / 255.0, 61.0 / 255.0, 58.0 / 255.0, 1.0), CornerRadii.EMPTY, Insets.EMPTY))));
				}
			}
		}
	}

	private volatile AtomicLong bytesSent = new AtomicLong(0);
	private void sendLobby(String remoteAddress, int remotePort, byte[] data) {
		try {
			DatagramPacket datagramPacket = new DatagramPacket(data, data.length);
			datagramPacket.setAddress(InetAddress.getByName(remoteAddress));
			datagramPacket.setPort(remotePort);
			lobbySocket.send(datagramPacket);

			bytesSent.addAndGet(data.length);
		} catch (IOException e) {
			Logger.warning("Error while sending UDP Packet.", e);
		}
	}

	private void sendLobby(String remoteAddress, int remotePort, ByteArrayOutputStream dataStream) {
		sendLobby(remoteAddress, remotePort, dataStream.toByteArray());
	}

	public void stop() {
		running = false;
		if (lobbySocket != null) {
			lobbySocket.close();
		}
		try {
			if (gpgnetSocket != null) {
				gpgnetSocket.close();
			}
		} catch (IOException ignored) {}
	}
}
