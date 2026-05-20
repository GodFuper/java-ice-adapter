package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;

@UtilityClass
public class LockUtil {
    public void executeWithLock(Lock lock, Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    public void tryExecuteWithLock(Lock lock, Runnable task) {
        if (lock.tryLock()) {
            try {
                task.run();
            } finally {
                lock.unlock();
            }
        }
    }

    public <T> T executeWithLock(Lock lock, Callable<T> task) {
        lock.lock();
        try {
            return task.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}
