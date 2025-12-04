package com.faforever.iceadapter.debug;

import javafx.beans.property.*;
import lombok.Data;

import java.util.Objects;

@Data
public class PeerInfo {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty login = new SimpleStringProperty();
    private final StringProperty connected = new SimpleStringProperty();
    private final StringProperty localCand = new SimpleStringProperty();
    private final StringProperty remoteCand = new SimpleStringProperty();
    private final StringProperty pairConnection = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty();
    private final StringProperty agent = new SimpleStringProperty();
    private final StringProperty offer = new SimpleStringProperty();
    private final StringProperty rtt = new SimpleStringProperty();
    private final StringProperty lastRecv = new SimpleStringProperty();
    private final StringProperty echosReceived = new SimpleStringProperty();
    private final BooleanProperty allowHost = new SimpleBooleanProperty();
    private final BooleanProperty allowReflexive = new SimpleBooleanProperty();
    private final BooleanProperty allowRelay = new SimpleBooleanProperty();

    // Конструктор
    public PeerInfo(int id, String login) {
        this.id.set(id);
        this.login.set(login);
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) object;
        return Objects.equals(id.get(), peerInfo.id.get());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id.get());
    }
}
