package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.Peer;

import java.util.concurrent.CompletableFuture;

public interface IceAsync {
    CompletableFuture<Void> runAsync(Peer peer, Runnable runnable);

    CompletableFuture<Void> runAsyncDelay(Peer peer, Runnable runnable, int delayMs);

}
