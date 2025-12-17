package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.OneIceServer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@Data
public class OneIceServerWrapper {
    private final OneIceServer server;
    private final StringProperty type;
    private final StringProperty transport;
    private final StringProperty address;
    private final StringProperty rtt;
    private final BooleanProperty enabled;

    public OneIceServerWrapper(OneIceServer server) {
        this.server = server;
        this.type = new SimpleStringProperty(server.getType().name());
        this.transport = new SimpleStringProperty(server.getAddress().getTransport().name());
        this.address = new SimpleStringProperty(server.getAddress().getHostName() + ":" + server.getAddress().getPort());
        this.rtt = new SimpleStringProperty(server.strTripTime());
        this.enabled = new SimpleBooleanProperty(server.isEnabled());

        // Bidirectional binding where needed
        this.enabled.addListener((obs, oldVal, newVal) -> server.setEnabled(newVal));
    }
}
