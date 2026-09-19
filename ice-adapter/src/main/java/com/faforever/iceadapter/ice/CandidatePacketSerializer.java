package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.util.CandidateUtil;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

public class CandidatePacketSerializer extends JsonSerializer<CandidatePacket> {

    @Override
    public void serialize(CandidatePacket value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeStartObject();

        // Standard WebRTC candidate fields (understood by Go pion/webrtc and faf-pioneer)
        String candidateStr = CandidateUtil.candidatePacketToWebRtcString(value);
        gen.writeStringField("candidate", candidateStr != null ? candidateStr : "");
        gen.writeStringField("sdpMid", "0");
        gen.writeNumberField("sdpMLineIndex", 0);

        // Legacy fields for backward compatibility with older Java FAF clients / ice-adapters
        if (value.foundation() != null) {
            gen.writeStringField("foundation", value.foundation());
        }
        if (value.protocol() != null) {
            gen.writeStringField("protocol", value.protocol());
        }
        gen.writeNumberField("priority", value.priority());
        if (value.ip() != null) {
            gen.writeStringField("ip", value.ip());
        }
        gen.writeNumberField("port", value.port());
        if (value.type() != null) {
            gen.writeStringField("type", value.type().name());
        }
        gen.writeNumberField("generation", value.generation());
        if (value.id() != null) {
            gen.writeStringField("id", value.id());
        }
        if (value.relAddr() != null) {
            gen.writeStringField("relAddr", value.relAddr());
        }
        gen.writeNumberField("relPort", value.relPort());

        gen.writeEndObject();
    }
}
