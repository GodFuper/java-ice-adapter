package com.faforever.iceadapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import picocli.CommandLine.Option;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IceOptions {
    @Option(names = "--id", required = true, description = "set the ID of the local player")
    private int id;

    @Option(names = "--game-id", required = true, description = "set the ID of the game")
    private int gameId;

    @Option(names = "--login", required = true, description = "set the login of the local player e.g. \"Rhiza\"")
    private String login;

    @Option(names = "--rpc-port", defaultValue = "7236", description = "set the port of internal JSON-RPC server")
    private int rpcPort;

    @Option(names = "--gpgnet-port", defaultValue = "0", description = "set the port of internal GPGNet server")
    private int gpgnetPort;

    @Option(names = "--lobby-port",
            defaultValue = "0",
            description = "set the port the game lobby should use for incoming UDP packets from the PeerRelay")
    private int lobbyPort;

    @Option(names = "--force-relay", description = "force the usage of relay candidates only")
    private boolean forceRelay;

    @Option(names = "--debug-window", description = "activate the debug window")
    private boolean debugWindow;

    @Option(names = "--info-window", description = "activate the info window")
    private boolean infoWindow;

    @Option(names = "--delay-ui",
            defaultValue = "0",
            description = "delays the launch of the info and debug window (in ms)")
    private int delayUi;

    @Option(
            names = "--ping-count",
            defaultValue = "1",
            description = "number of times to ping each turn server to determine latency")
    private int pingCount;

    @Option(names = "--acceptable-latency",
            defaultValue = "250.0",
            description = "number of times to ping each turn server to determine latency")
    private double acceptableLatency;

    @Option(names = "--telemetry-server",
            defaultValue = "wss://ice-telemetry.faforever.com",
            description = "Telemetry server to connect to")
    private String telemetryServer;

    @Option(names = "--manual-combination-connection",
            defaultValue = "true",
            description = "Manually editing the connection combination in the UI")
    private boolean manualCombinationConnection;

    @Option(names = "--manual-strategy-connection",
            defaultValue = "true",
            description = "Manually editing the connection strategy in the UI")
    private boolean manualStrategyConnection;

    @Option(names = "--additional-info-peer",
            defaultValue = "false",
            description = "Additional information about Peer in the UI")
    private boolean additionalInfoPeer;

    @Option(names = "--host-mode",
            defaultValue = "true",
            description = "Enable host-based P2P connection mode where one player acts as a host and others connect through them")
    private boolean hostMode;

    @Option(names = "--custom-reliable-udp",
            defaultValue = "false",
            description = "Enable custom reliable UDP")
    private boolean customReliableUdp;
}
