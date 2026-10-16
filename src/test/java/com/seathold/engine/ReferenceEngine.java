package com.seathold.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Single-threaded specification model.
 *
 * This implementation deliberately favors simple derived state over
 * production optimizations such as cached counts or fine-grained locks.
 *
 * Policies:
 * - A hold expires when now > expiresAt.
 * - Expired holds do not consume active quota.
 * - An expired hold remains confirmable until explicitly released
 *   or successfully replaced.
 * - A failed hold or batch does not invalidate a prior hold.
 * - Batch results follow first-occurrence input order.
 */
class ReferenceEngine implements SeatHoldEngine {

    private static final int MAX_HOLDS_PER_USER = 4;

    private static final class Entry {
        private final String holdId;
        private final String seatId;
        private final String userId;
        private final Instant expiresAt;

        private boolean released;
        private boolean confirmed;

        private Entry(
                String holdId,
                String seatId,
                String userId,
                Instant expiresAt
        ) {
            this.holdId = holdId;
            this.seatId = seatId;
            this.userId = userId;
            this.expiresAt = expiresAt;
        }
    }

    private final Supplier<Instant> clock;

    private final Map<String, Entry> holdsById = new HashMap<>();
    private final Map<String, Entry> currentBySeat = new HashMap<>();
    private final Map<String, String> soldSeats = new HashMap<>();

    private long nextId;

    ReferenceEngine(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public Result<String> hold(
            String seatId,
            String userId,
            long ttlSeconds
    ) {
        Instant now = clock.get();

        if (soldSeats.containsKey(seatId)) {
            return Result.err("seat already sold");
        }

        Entry current = currentBySeat.get(seatId);

        if (isActive(current, now)) {
            if (current.userId.equals(userId)) {
                return Result.ok(current.holdId);
            }

            return Result.err("seat held by another user");
        }

        if (activeCount(userId, now) >= MAX_HOLDS_PER_USER) {
            return Result.err("user hold limit reached");
        }

        /*
         * Calculate expiry before changing existing state.
         * This also avoids mutating state if Instant arithmetic throws.
         */
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        Entry created = createHold(
                seatId,
                userId,
                expiresAt
        );

        return Result.ok(created.holdId);
    }

    @Override
    public Result<String> confirm(String holdId) {
        Entry hold = holdsById.get(holdId);

        if (hold == null) {
            return Result.err("hold not found");
        }

        if (hold.confirmed) {
            return Result.ok("ok");
        }

        if (hold.released) {
            return Result.err("hold released");
        }

        if (currentBySeat.get(hold.seatId) != hold) {
            return Result.err("seat retaken");
        }

        if (soldSeats.containsKey(hold.seatId)) {
            return Result.err("seat already sold");
        }

        /*
         * Expiry is intentionally not checked:
         * the latest unreleased hold may confirm after expiry.
         */
        hold.confirmed = true;
        currentBySeat.remove(hold.seatId);
        soldSeats.put(hold.seatId, hold.userId);

        return Result.ok("ok");
    }

    @Override
    public Result<String> release(String holdId) {
        Entry hold = holdsById.get(holdId);

        if (hold == null) {
            return Result.err("hold not found");
        }

        if (hold.confirmed) {
            return Result.err("cannot release confirmed hold");
        }

        if (hold.released) {
            return Result.ok("ok");
        }

        hold.released = true;

        /*
         * Conditional removal ensures an old hold cannot remove
         * a newer reservation on the same seat.
         */
        currentBySeat.remove(hold.seatId, hold);

        return Result.ok("ok");
    }

    @Override
    public SeatStatus seatStatus(String seatId) {
        String buyer = soldSeats.get(seatId);

        if (buyer != null) {
            return new SeatStatus.Sold(buyer);
        }

        Entry current = currentBySeat.get(seatId);

        if (isActive(current, clock.get())) {
            return new SeatStatus.Held(
                    current.userId,
                    current.expiresAt
            );
        }

        return new SeatStatus.Free();
    }

    @Override
    public Result<List<String>> holdMany(
            String userId,
            List<String> seatIds,
            long ttlSeconds
    ) {
        /*
         * Preserve the production API's existing empty/null-list policy.
         * Null elements and other invalid arguments are outside this
         * model's generated input domain.
         */
        if (seatIds == null || seatIds.isEmpty()) {
            return Result.ok(List.of());
        }

        List<String> uniqueSeats = new ArrayList<>(
                new LinkedHashSet<>(seatIds)
        );

        Instant now = clock.get();
        int additionalSeats = 0;

        /*
         * Phase 1: validate everything without mutation.
         */
        for (String seatId : uniqueSeats) {
            if (soldSeats.containsKey(seatId)) {
                return Result.err("seat already sold");
            }

            Entry current = currentBySeat.get(seatId);

            if (isActive(current, now)) {
                if (!current.userId.equals(userId)) {
                    return Result.err("seat held by another user");
                }
            } else {
                additionalSeats++;
            }
        }

        if (activeCount(userId, now) + additionalSeats
                > MAX_HOLDS_PER_USER) {

            return Result.err("batch exceeds user hold limit");
        }

        Instant expiresAt = additionalSeats == 0
                ? null
                : now.plusSeconds(ttlSeconds);

        /*
         * Phase 2: apply the already-validated transaction.
         */
        List<String> holdIds = new ArrayList<>();

        for (String seatId : uniqueSeats) {
            Entry current = currentBySeat.get(seatId);

            if (isActive(current, now)) {
                holdIds.add(current.holdId);
            } else {
                Entry created = createHold(
                        seatId,
                        userId,
                        expiresAt
                );

                holdIds.add(created.holdId);
            }
        }

        return Result.ok(holdIds);
    }

    private boolean isActive(Entry hold, Instant now) {
        return hold != null
                && !hold.released
                && !hold.confirmed
                && !now.isAfter(hold.expiresAt);
    }

    private long activeCount(String userId, Instant now) {
        return currentBySeat.values()
                .stream()
                .filter(hold -> hold.userId.equals(userId))
                .filter(hold -> isActive(hold, now))
                .count();
    }

    private Entry createHold(
            String seatId,
            String userId,
            Instant expiresAt
    ) {
        Entry previous = currentBySeat.get(seatId);

        /*
         * This method is called only after successful validation.
         * A successful replacement permanently invalidates the old ID.
         */
        if (previous != null) {
            previous.released = true;
        }

        Entry created = new Entry(
                "reference-" + (++nextId),
                seatId,
                userId,
                expiresAt
        );

        holdsById.put(created.holdId, created);
        currentBySeat.put(seatId, created);

        return created;
    }
}