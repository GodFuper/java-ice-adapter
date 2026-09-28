package com.faforever.iceadapter.icebreaker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EventMessageDto.Connected.class, name = "connected"),
    @JsonSubTypes.Type(value = EventMessageDto.Candidates.class, name = "candidates"),
    @JsonSubTypes.Type(value = EventMessageDto.PeerClosing.class, name = "peerClosing")
})
public interface EventMessageDto {

    long gameId();

    long senderId();

    Long recipientId();

    @JsonTypeName("connected")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Connected(long gameId, long senderId, Long recipientId) implements EventMessageDto {}

    @JsonTypeName("candidates")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidates(long gameId, long senderId, Long recipientId, JsonNode session, JsonNode candidates)
            implements EventMessageDto {}

    @JsonTypeName("peerClosing")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PeerClosing(long gameId, long senderId, Long recipientId) implements EventMessageDto {}
}
