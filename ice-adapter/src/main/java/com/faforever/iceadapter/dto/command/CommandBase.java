package com.faforever.iceadapter.dto.command;

import com.faforever.iceadapter.dto.command.relay.auto.info.RelayPingCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_client.*;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.RpcMessageFromServerPeerCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerConnectingCommand;
import com.faforever.iceadapter.dto.command.relay.manual.from_server.ServerPeerStatusCommand;
import com.faforever.iceadapter.dto.command.relay.manual.info.InfoRelayStatusCommand;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.ObjectMapperUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static com.faforever.iceadapter.ice.peer.modules.other.CommandModule.COMMAND_BASE;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConnectionLostRelayServerCommand.class, name = "connect_lost_relay"),
        @JsonSubTypes.Type(value = RpcMessageFromClientPeerCommand.class, name = "rpc_message_from_client"),
        @JsonSubTypes.Type(value = SetIceStateCommand.class, name = "set_ice_state"),
        @JsonSubTypes.Type(value = StartRelayServerCommand.class, name = "start_relay"),
        @JsonSubTypes.Type(value = StopRelayServerCommand.class, name = "stop_relay"),
        @JsonSubTypes.Type(value = SetAllowCombinationCommand.class, name = "set_allow_combination"),
        @JsonSubTypes.Type(value = RpcMessageFromServerPeerCommand.class, name = "rpc_message_from_server"),
        @JsonSubTypes.Type(value = ServerPeerConnectingCommand.class, name = "server_peer_connecting"),
        @JsonSubTypes.Type(value = ServerPeerStatusCommand.class, name = "server_peer_status"),
        @JsonSubTypes.Type(value = InfoRelayStatusCommand.class, name = "info_relay_status"),
        @JsonSubTypes.Type(value = RelayPingCommand.class, name = "relay_ping")
})
public abstract class CommandBase {
    private static final boolean COMPRESSION = true;

    public abstract void execute(Peer peer);

    @JsonIgnore
    public boolean isOnlyDirect() {
        return false;
    }

    public byte[] bytes() {
        return ObjectMapperUtil.toBytesAndAddFirstByte((byte) COMMAND_BASE, this, COMPRESSION);
    }

    public static CommandBase initCommand(byte[] bytes) {
        return ObjectMapperUtil.fromBytesAndWithOutFirstByte(bytes, CommandBase.class, COMPRESSION);
    }
}
