package com.faforever.iceadapter.dto.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.commons.lang3.StringUtils;
import org.ice4j.ice.CandidateType;

import java.io.IOException;

public class CandidateTypeSerializer {

    public static class Serializer extends JsonSerializer<CandidateType> {
        @Override
        public void serialize(CandidateType value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value != null ? value.name() : null);
        }
    }

    public static class Deserializer extends JsonDeserializer<CandidateType> {
        @Override
        public CandidateType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            if (StringUtils.isEmpty(value)) {
                return null;
            }
            try {
                return CandidateType.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid CandidateType: %s".formatted(value), e);
            }
        }
    }
}
