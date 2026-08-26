package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.KcpStatistics;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@Data
public class KcpPeerView {
    private final IntegerProperty peerId = new SimpleIntegerProperty(-1);
    private final StringProperty login = new SimpleStringProperty("-");

    private final StringProperty conv = new SimpleStringProperty("-");

    private final StringProperty srttMs = new SimpleStringProperty("-");
    private final StringProperty rttvarMs = new SimpleStringProperty("-");
    private final StringProperty rtoMs = new SimpleStringProperty("-");
    private final StringProperty cwnd = new SimpleStringProperty("-");
    private final StringProperty sndWnd = new SimpleStringProperty("-");
    private final StringProperty rcvWnd = new SimpleStringProperty("-");
    private final StringProperty waitSnd = new SimpleStringProperty("-");
    private final StringProperty state = new SimpleStringProperty("-");
    private final StringProperty xmit = new SimpleStringProperty("-");
    private final StringProperty maxSegXmit = new SimpleStringProperty("-");
    private final StringProperty resendCount = new SimpleStringProperty("-");
    private final StringProperty fastResendCount = new SimpleStringProperty("-");
    private final StringProperty fastackCount = new SimpleStringProperty("-");
    private final StringProperty ssthresh = new SimpleStringProperty("-");
    private final StringProperty unackedPackets = new SimpleStringProperty("-");
    private final StringProperty sndNxt = new SimpleStringProperty("-");
    private final StringProperty sndUna = new SimpleStringProperty("-");
    private final StringProperty rcvNxt = new SimpleStringProperty("-");
    private final StringProperty sndQueueSize = new SimpleStringProperty("-");
    private final StringProperty rcvQueueSize = new SimpleStringProperty("-");
    private final StringProperty nextUpdateMs = new SimpleStringProperty("-");
    private final StringProperty bytesSentBytes = new SimpleStringProperty("-");
    private final StringProperty bytesReceivedBytes = new SimpleStringProperty("-");

    private final StringProperty deadLinkDetected = new SimpleStringProperty("-");
    private final StringProperty consecutiveSoftResync = new SimpleStringProperty("-");
    private final StringProperty receiveGapSince = new SimpleStringProperty("-");
    private final StringProperty softDroppedSegments = new SimpleStringProperty("-");
    private final StringProperty softResyncCount = new SimpleStringProperty("-");

    public KcpPeerView(int peerId, String login) {
        this.peerId.set(peerId);
        this.login.set(login);
    }

    /**
     * Updates this view's properties from the peer's current KCP statistics.
     */
    public void update(Peer peer) {
        KcpStatistics stats = peer.getKcpStatistics();
        conv.set(String.valueOf(stats.getConv()));
        srttMs.set(String.valueOf(stats.getSrttMs()));
        rttvarMs.set(String.valueOf(stats.getRttvarMs()));
        rtoMs.set(String.valueOf(stats.getRtoMs()));
        cwnd.set(String.valueOf(stats.getCwnd()));
        sndWnd.set(String.valueOf(stats.getSndWnd()));
        rcvWnd.set(String.valueOf(stats.getRcvWnd()));
        waitSnd.set(String.valueOf(stats.getWaitSnd()));
        xmit.set(String.valueOf(stats.getXmit()));
        maxSegXmit.set(String.valueOf(stats.getMaxSegXmit()));
        resendCount.set(String.valueOf(stats.getResendCount()));
        fastResendCount.set(String.valueOf(stats.getFastResendCount()));
        fastackCount.set(String.valueOf(stats.getFastackCount()));
        ssthresh.set(String.valueOf(stats.getSsthresh()));
        unackedPackets.set(String.valueOf(stats.getUnackedPackets()));
        sndNxt.set(String.valueOf(stats.getSndNxt()));
        sndUna.set(String.valueOf(stats.getSndUna()));
        rcvNxt.set(String.valueOf(stats.getRcvNxt()));
        sndQueueSize.set(String.valueOf(stats.getSndQueueSize()));
        rcvQueueSize.set(String.valueOf(stats.getRcvQueueSize()));
        nextUpdateMs.set(String.valueOf(stats.getTimeToNextUpdateMs()));
        bytesSentBytes.set(formatBytes(stats.getBytesSentBytes()));
        bytesReceivedBytes.set(formatBytes(stats.getBytesReceivedBytes()));
        deadLinkDetected.set(stats.isDeadLinkDetected() ? "Yes" : "No");
        consecutiveSoftResync.set(String.valueOf(stats.getConsecutiveSoftResync()));
        if (stats.getReceiveGapSince() >= 0) {
            receiveGapSince.set(String.valueOf(System.currentTimeMillis() - stats.getReceiveGapSince()) + " ms");
        } else {
            receiveGapSince.set("—");
        }
        softDroppedSegments.set(String.valueOf(stats.getSoftDroppedSegments()));
        softResyncCount.set(String.valueOf(stats.getSoftResyncCount()));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
