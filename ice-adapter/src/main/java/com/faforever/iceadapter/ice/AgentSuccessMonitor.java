package com.faforever.iceadapter.ice;

import java.util.concurrent.CompletableFuture;

public interface AgentSuccessMonitor {

    CompletableFuture<Boolean> start();

    void shutdown();
}
