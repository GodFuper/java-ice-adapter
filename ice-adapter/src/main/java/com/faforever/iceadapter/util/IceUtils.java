package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

import java.util.Optional;

@UtilityClass
public class IceUtils {

    public Optional<Component> getFirstComponent(IceMediaStream mediaStream) {
        if (mediaStream == null) {
            return Optional.empty();
        }

        return mediaStream.getComponents().stream().findFirst();
    }
}
