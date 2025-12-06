package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.services.IceAsync;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class IceAsyncImpl implements IceAsync {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;

    @Override
    public CompletableFuture<Void> runAsync(Peer peer, Runnable runnable) {
        return CompletableFuture.runAsync(() -> doBeforeRun(peer, runnable), executorService);
    }

    @Override
    public CompletableFuture<Void> runAsyncDelay(Peer peer, Runnable runnable, int delayMs) {
        return CompletableFuture.runAsync(() -> doBeforeRun(peer, runnable),
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
    }

    void doBeforeRun(Peer peer, Runnable runnable) {
        String threadName = Thread.currentThread().getName();
        Thread.currentThread().setName("%s-%s".formatted(threadName, getNameForThread(peer)));
        runnable.run();
    }

    private String getNameForThread(Peer peer) {
        return Optional.ofNullable(peer)
                .map(Peer::getPeerIdentifier)
                .orElse("");
    }
}
