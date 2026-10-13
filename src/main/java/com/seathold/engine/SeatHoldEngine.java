package com.seathold.engine;

import java.time.Instant;
import java.util.List;

/**
 * Public contract for the seat-hold engine. Implementations must be
 * thread-safe; the clock is injected so tests never call Thread.sleep.
 */
public interface SeatHoldEngine {

    /**
     * Attempt to hold {@code seatId} for {@code userId} for {@code ttlSeconds}.
     *
     * <p>Idempotency: if the same user already holds the same seat the
     * original hold-id is returned unchanged (R5). The TTL is NOT reset (R4).
     *
     * @return Ok(holdId) or Err(reason)
     */
    Result<String> hold(String seatId, String userId, long ttlSeconds);

    /**
     * Confirm (finalise the sale of) a hold. Works even after the TTL has
     * lapsed provided nobody else grabbed the seat in the meantime (R3).
     *
     * @return Ok("ok") or Err(reason)
     */
    Result<String> confirm(String holdId);

    /**
     * Explicitly release a hold before paying. Frees the seat immediately.
     *
     * @return Ok("ok") or Err(reason)
     */
    Result<String> release(String holdId);

    /**
     * Current observable state of a seat.
     */
    SeatStatus seatStatus(String seatId);

    /**
     * Atomically hold every seat in the list for {@code userId} (R6).
     * All-or-nothing: if any seat cannot be held the whole batch is rolled back.
     *
     * @return Ok(list of holdIds) or Err(reason)
     */
    Result<List<String>> holdMany(String userId, List<String> seatIds, long ttlSeconds);
}
