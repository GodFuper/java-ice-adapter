package com.faforever.iceadapter.util;

import com.faforever.iceadapter.services.impl.IceAsyncImpl;
import lombok.experimental.UtilityClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Used in {@link IceAsyncImpl}. We have to use Executors.defaultThreadFactory() since Ice4J is not allowed to use virtual threads in Java 21.
 */
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
