package com.ndys.jvmlab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class LeakService {

    private static final Logger log = LoggerFactory.getLogger(LeakService.class);

    private static final int CHUNK_SIZE_BYTES = 256 * 1024;
    private static final long PERIOD_MILLIS = 100;

    private final List<byte[]> leaked = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "leak-scheduler"));

    private ScheduledFuture<?> task;

    public synchronized boolean start() {
        if (task != null && !task.isDone()) {
            return false;
        }
        task = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        leaked.add(new byte[CHUNK_SIZE_BYTES]);
                    } catch (Throwable t) {
                        log.error("leak task failed, stopping", t);
                        throw t;
                    }
                },
                0,
                PERIOD_MILLIS,
                TimeUnit.MILLISECONDS
        );
        return true;
    }

    public synchronized boolean stop() {
        if (task == null || task.isDone()) {
            return false;
        }
        task.cancel(false);
        return true;
    }

    public void clear() {
        leaked.clear();
    }

    public LeakStatus status() {
        boolean running = task != null && !task.isDone();
        long chunkCount = leaked.size();
        long approxBytes = chunkCount * CHUNK_SIZE_BYTES;
        return new LeakStatus(running, chunkCount, approxBytes);
    }

    public record LeakStatus(boolean running, long chunkCount, long approxBytes) {
    }
}
