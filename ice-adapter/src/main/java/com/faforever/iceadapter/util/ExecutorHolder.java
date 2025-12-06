package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@UtilityClass
public class ExecutorHolder {

    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Executors.defaultThreadFactory());
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(100, Executors.defaultThreadFactory());

    public ExecutorService getExecutor() {
        return executorService;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutorService;
    }
}
