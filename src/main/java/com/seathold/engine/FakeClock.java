package com.seathold.engine;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A fake clock whose current time can be moved forward by tests.
 * Backed by an AtomicLong (milliseconds since epoch) so it is thread-safe
 * and multiple threads in a stress test can all read a consistent "now".
 */
public class FakeClock implements Supplier<Instant> {

    private final AtomicLong epochMillis;

    public FakeClock(Instant startTime) {
        this.epochMillis = new AtomicLong(startTime.toEpochMilli());
    }

    @Override
    public Instant get() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    /** Move the clock forward by {@code seconds} seconds. */
    public void advanceSeconds(long seconds) {
        epochMillis.addAndGet(seconds * 1_000L);
    }

    /** Move the clock forward by {@code millis} milliseconds. */
    public void advanceMillis(long millis) {
        epochMillis.addAndGet(millis);
    }

    /** Set the clock to a specific instant. */
    public void set(Instant instant) {
        epochMillis.set(instant.toEpochMilli());
    }
}
