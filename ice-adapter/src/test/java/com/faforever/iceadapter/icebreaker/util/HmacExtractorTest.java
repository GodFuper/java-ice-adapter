package com.faforever.iceadapter.icebreaker.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HmacExtractorTest {

    private String createJwt(String payloadJson) {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummySignature";
    }

    @Test
    void extractHmac_validTokenWithHmac_returnsHmac() {
        String jwt = createJwt("{\"sub\":\"user123\",\"ext\":{\"hmac\":\"abc123secret\"}}");

        Optional<String> hmac = HmacExtractor.extractHmac(jwt);

        assertTrue(hmac.isPresent());
        assertEquals("abc123secret", hmac.get());
    }

    @Test
    void extractHmac_validTokenWithoutExt_returnsEmpty() {
        String jwt = createJwt("{\"sub\":\"user123\"}");

        Optional<String> hmac = HmacExtractor.extractHmac(jwt);

        assertFalse(hmac.isPresent());
    }

    @Test
    void extractHmac_validTokenWithoutHmacField_returnsEmpty() {
        String jwt = createJwt("{\"sub\":\"user123\",\"ext\":{}}");

        Optional<String> hmac = HmacExtractor.extractHmac(jwt);

        assertFalse(hmac.isPresent());
    }

    @Test
    void extractHmac_validTokenWithNullHmac_returnsEmpty() {
        String jwt = createJwt("{\"sub\":\"user123\",\"ext\":{\"hmac\":null}}");

        Optional<String> hmac = HmacExtractor.extractHmac(jwt);

        assertFalse(hmac.isPresent());
    }

    @Test
    void extractHmac_validTokenWithEmptyHmac_returnsEmpty() {
        String jwt = createJwt("{\"sub\":\"user123\",\"ext\":{\"hmac\":\"   \"}}");

        Optional<String> hmac = HmacExtractor.extractHmac(jwt);

        assertFalse(hmac.isPresent());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "invalidTokenWithoutDots",
                "headerOnly.",
                "header.invalidBase64!@#$.sig",
                "header.bm90LWpzb24=.sig"
            })
    void extractHmac_malformedTokens_returnsEmpty(String invalidToken) {
        Optional<String> hmac = HmacExtractor.extractHmac(invalidToken);

        assertFalse(hmac.isPresent());
    }
}
