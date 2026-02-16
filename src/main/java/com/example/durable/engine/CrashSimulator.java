package com.example.durable.engine;

import java.util.concurrent.atomic.AtomicLong;

public final class CrashSimulator {
    private final long crashAfter;
    private final AtomicLong counter = new AtomicLong(0);

    public CrashSimulator(long crashAfter) {
        this.crashAfter = crashAfter;
    }

    public void afterStepStarted() {
        if (crashAfter <= 0) {
            return;
        }
        long current = counter.incrementAndGet();
        if (current == crashAfter) {
            Runtime.getRuntime().halt(1);
        }
    }
}
