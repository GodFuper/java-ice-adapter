package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.*;

@RequiredArgsConstructor
public class IceAsyncImpl implements IceAsync {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;

    @Override
    public CompletableFuture<Void> runAsync(String methodName, Peer peer, Runnable runnable) {
        return CompletableFuture.runAsync(() -> doBeforeRun(methodName, peer, runnable), executorService);
    }

    @Override
    public CompletableFuture<Void> runAsync(Peer peer, Runnable runnable) {
        return CompletableFuture.runAsync(() -> doBeforeRun(peer, runnable), executorService);
    }

    @Override
    public CompletableFuture<Void> runAsyncDelay(Peer peer, Runnable runnable, int delayMs) {
        return CompletableFuture.runAsync(() -> doBeforeRun(peer, runnable),
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Peer peer, Runnable runnable, long delayMs) {
        return scheduledExecutorService.scheduleAtFixedRate(() -> doBeforeRun(peer, runnable), 0, delayMs, TimeUnit.MILLISECONDS);
    }

    private void doBeforeRun(String methodName, Peer peer, Runnable runnable) {
        Thread.currentThread().setName(createNameForThread(methodName, getNameForThread(peer)));
        runnable.run();
    }

    private void doBeforeRun(Peer peer, Runnable runnable) {
        doBeforeRun(null, peer, runnable);
    }

    private String createNameForThread(Object... args) {
        StringJoiner joiner = new StringJoiner("-");
        for (Object arg : args) {
            if (arg != null) {
                joiner.add(String.valueOf(arg));
            }
        }
        return joiner.toString();
    }

    private String getNameForThread(Peer peer) {
        return Optional.ofNullable(peer)
                .map(Peer::getPeerIdentifier)
                .orElse(null);
    }
}
