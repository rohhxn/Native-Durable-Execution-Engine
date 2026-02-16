package com.example.durable.engine;

import java.util.concurrent.atomic.AtomicLong;

public final class SequenceTracker {
    private final AtomicLong counter = new AtomicLong(0);

    public long reserve() {
        return counter.getAndIncrement();
    }

    public long current() {
        return counter.get();
    }
}
