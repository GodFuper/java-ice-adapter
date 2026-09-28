package com.faforever.iceadapter.icebreaker.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class HmacExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HmacExtractor() {
        // utility class
    }

    public static Optional<String> extractHmac(String jwtToken) {
        if (jwtToken == null || jwtToken.isBlank()) {
            return Optional.empty();
        }

        String[] parts = jwtToken.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }

        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            try {
                payloadBytes = Base64.getDecoder().decode(parts[1]);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        try {
            JsonNode claims = OBJECT_MAPPER.readTree(payloadBytes);
            String hmac = claims.path("ext").path("hmac").asText(null);
            if (hmac == null || hmac.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(hmac);
        } catch (Exception e) {
            log.debug("Failed to extract HMAC from JWT token", e);
            return Optional.empty();
        }
    }
}
