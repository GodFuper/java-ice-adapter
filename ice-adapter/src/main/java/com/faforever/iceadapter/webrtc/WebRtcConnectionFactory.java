package com.faforever.iceadapter.webrtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import lombok.extern.slf4j.Slf4j;

/**
 * Singleton that initializes PeerConnectionFactory once and provides RTCPeerConnection instances.
 */
@Slf4j
public final class WebRtcConnectionFactory {

    private static volatile WebRtcConnectionFactory instance;
    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDeviceModule;

    private WebRtcConnectionFactory() {
    }

    public static WebRtcConnectionFactory getInstance() {
        WebRtcConnectionFactory result = instance;
        if (result == null) {
            synchronized (WebRtcConnectionFactory.class) {
                result = instance;
                if (result == null) {
                    result = new WebRtcConnectionFactory();
                    result.init();
                    instance = result;
                }
            }
        }
        return result;
    }

    private void init() {
        try {
            audioDeviceModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
            factory = new PeerConnectionFactory(audioDeviceModule);
            log.info("WebRtcConnectionFactory initialized");
        } catch (Exception e) {
            log.error("Failed to initialize WebRtcConnectionFactory", e);
            throw new RuntimeException("WebRTC initialization failed", e);
        }
    }

    public PeerConnectionFactory getFactory() {
        return factory;
    }

    /**
     * Shutdown the factory (called on adapter close).
     */
    public synchronized void shutdown() {
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Exception e) {
                log.warn("Error disposing PeerConnectionFactory", e);
            }
            factory = null;
        }
        if (audioDeviceModule != null) {
            try {
                audioDeviceModule.dispose();
            } catch (Exception e) {
                log.warn("Error disposing AudioDeviceModule", e);
            }
            audioDeviceModule = null;
        }
        instance = null;
        log.info("WebRtcConnectionFactory shut down");
    }
}
