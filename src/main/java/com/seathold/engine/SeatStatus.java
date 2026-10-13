package com.seathold.engine;

import java.time.Instant;

/**
 * Describes what we know about a seat at any given moment.
 */
public sealed interface SeatStatus permits SeatStatus.Free, SeatStatus.Held, SeatStatus.Sold {

    record Free() implements SeatStatus {}

    record Held(String userId, Instant expiresAt) implements SeatStatus {}

    record Sold(String userId) implements SeatStatus {}
}
