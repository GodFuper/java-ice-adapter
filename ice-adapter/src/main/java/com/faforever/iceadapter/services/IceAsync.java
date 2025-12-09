package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.peer.Peer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

public interface IceAsync {
    CompletableFuture<Void> runAsync(String methodName, Peer peer, Runnable runnable);

    CompletableFuture<Void> runAsync(Peer peer, Runnable runnable);

    CompletableFuture<Void> runAsyncDelay(Peer peer, Runnable runnable, int delayMs);

    ScheduledFuture<?> scheduleAtFixedRate(Peer peer, Runnable runnable, long delayMs);
}
