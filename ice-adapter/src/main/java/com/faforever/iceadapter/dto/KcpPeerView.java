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

    private final StringProperty srttMs = new SimpleStringProperty("-");
    private final StringProperty rttvarMs = new SimpleStringProperty("-");
    private final StringProperty rtoMs = new SimpleStringProperty("-");
    private final StringProperty cwnd = new SimpleStringProperty("-");
    private final StringProperty sndWnd = new SimpleStringProperty("-");
    private final StringProperty rcvWnd = new SimpleStringProperty("-");
    private final StringProperty waitSnd = new SimpleStringProperty("-");
    private final StringProperty xmit = new SimpleStringProperty("-");
    private final StringProperty maxSegXmit = new SimpleStringProperty("-");
    private final StringProperty state = new SimpleStringProperty("-");

    public KcpPeerView(int peerId, String login) {
        this.peerId.set(peerId);
        this.login.set(login);
    }

    /**
     * Updates this view's properties from the peer's current KCP statistics.
     */
    public void update(Peer peer) {
        KcpStatistics stats = peer.getKcpStatistics();
        srttMs.set(String.valueOf(stats.getSrttMs()));
        rttvarMs.set(String.valueOf(stats.getRttvarMs()));
        rtoMs.set(String.valueOf(stats.getRtoMs()));
        cwnd.set(String.valueOf(stats.getCwnd()));
        sndWnd.set(String.valueOf(stats.getSndWnd()));
        rcvWnd.set(String.valueOf(stats.getRcvWnd()));
        waitSnd.set(String.valueOf(stats.getWaitSnd()));
        xmit.set(String.valueOf(stats.getXmit()));
        maxSegXmit.set(String.valueOf(stats.getMaxSegXmit()));
        state.set(String.valueOf(stats.getState()));
    }
}
