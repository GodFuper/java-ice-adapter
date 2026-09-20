package com.faforever.iceadapter.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.iceadapter.webrtc.WebRtcSession.DataChannelStats;
import org.junit.jupiter.api.Test;

class WebRtcDataChannelViewTest {

    @Test
    void testUpdateWithDataChannelStats() {
        WebRtcDataChannelView view = new WebRtcDataChannelView(42, "PlayerOne", "gameData");
        DataChannelStats stats = new DataChannelStats("gameData", "open", 100, 150, 2048, 5120);

        view.update("PlayerOne", stats);

        assertEquals(42, view.getPeerId().get());
        assertEquals("PlayerOne", view.getLogin().get());
        assertEquals("gameData", view.getLabel().get());
        assertEquals("open", view.getState().get());
        assertEquals("100", view.getMessagesSent().get());
        assertEquals("150", view.getMessagesReceived().get());
        assertEquals("2.00 KB", view.getBytesSent().get());
        assertEquals("5.00 KB", view.getBytesReceived().get());
    }

    @Test
    void testUpdateWithNullStats() {
        WebRtcDataChannelView view = new WebRtcDataChannelView(42, "PlayerOne", "controlData");
        view.update("PlayerOne", null);

        assertEquals(42, view.getPeerId().get());
        assertEquals("PlayerOne", view.getLogin().get());
        assertEquals("controlData", view.getLabel().get());
        assertEquals("-", view.getState().get());
        assertEquals("-", view.getMessagesSent().get());
        assertEquals("-", view.getMessagesReceived().get());
        assertEquals("-", view.getBytesSent().get());
        assertEquals("-", view.getBytesReceived().get());
    }
}
