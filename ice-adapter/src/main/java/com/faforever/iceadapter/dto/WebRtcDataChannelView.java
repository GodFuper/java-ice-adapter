package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.webrtc.WebRtcSession.DataChannelStats;
import java.util.Locale;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@Data
public class WebRtcDataChannelView {
    private final IntegerProperty peerId = new SimpleIntegerProperty(-1);
    private final StringProperty login = new SimpleStringProperty("-");
    private final StringProperty label = new SimpleStringProperty("-");
    private final StringProperty state = new SimpleStringProperty("-");
    private final StringProperty messagesSent = new SimpleStringProperty("-");
    private final StringProperty messagesReceived = new SimpleStringProperty("-");
    private final StringProperty bytesSent = new SimpleStringProperty("-");
    private final StringProperty bytesReceived = new SimpleStringProperty("-");

    public WebRtcDataChannelView(int peerId, String login, String label) {
        this.peerId.set(peerId);
        this.login.set(login);
        this.label.set(label);
    }

    public void update(String login, DataChannelStats stats) {
        this.login.set(login);
        if (stats != null) {
            this.label.set(stats.getLabel());
            this.state.set(stats.getState());
            this.messagesSent.set(String.valueOf(stats.getMessagesSent()));
            this.messagesReceived.set(String.valueOf(stats.getMessagesReceived()));
            this.bytesSent.set(formatBytes(stats.getBytesSent()));
            this.bytesReceived.set(formatBytes(stats.getBytesReceived()));
        } else {
            this.state.set("-");
            this.messagesSent.set("-");
            this.messagesReceived.set("-");
            this.bytesSent.set("-");
            this.bytesReceived.set("-");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
