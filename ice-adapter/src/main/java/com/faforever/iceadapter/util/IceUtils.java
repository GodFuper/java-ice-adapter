package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.Peer;
import lombok.experimental.UtilityClass;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@UtilityClass
public class IceUtils {

    public Optional<Component> getFirstActiveComponent(Peer peer) {
        return Optional.ofNullable(peer)
                .map(Peer::getMediaStream)
                .flatMap(IceUtils::getFirstActiveComponent);
    }

    public Optional<Component> getFirstActiveComponent(IceMediaStream mediaStream) {
        if (mediaStream == null) {
            return Optional.empty();
        }

        return mediaStream.getComponents()
                .stream()
                .sorted()
                .filter(component -> Objects.nonNull(component.getSelectedPair()))
                .findFirst();
    }

    public List<Component> getActiveComponents(IceMediaStream mediaStream) {
        if (mediaStream == null) {
            return List.of();
        }

        return mediaStream.getComponents()
                .stream()
                .sorted()
                .filter(component -> Objects.nonNull(component.getSelectedPair()))
                .collect(Collectors.toList());
    }
}
