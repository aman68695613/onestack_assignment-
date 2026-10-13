package com.seathold.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Thread-safe, in-memory seat-hold engine.
 *
 * Locking strategy
 * ----------------
 * Two levels of locking:
 *
 * 1. Per-SEAT lock  — guards the seat's current Hold and the soldSeats entry.
 *    Every hold(), confirm(), release() and holdMany() acquires only the relevant
 *    seat lock(s). This maximises concurrency: two users on different seats never
 *    block each other.
 *
 * 2. Per-USER lock  — guards the user's hold count. Acquired BEFORE the seat
 *    lock whenever we might change the count. Lock ordering is always
 *    user-lock → seat-lock(s) to prevent deadlock.
 *
 * For holdMany with multiple seats we additionally sort seat ids before acquiring
 * seat locks so two threads with overlapping seat sets in opposite order cannot
 * deadlock each other.
 *
 * Rule-conflict decisions: see DECISIONS.md.
 */
public class SeatHoldEngineImpl implements SeatHoldEngine {

    private static final int MAX_HOLDS_PER_USER = 4;

    private final Supplier<Instant> clock;

    // seatId -> active Hold on that seat
    private final Map<String, Hold>    activeHolds   = new ConcurrentHashMap<>();
    // holdId -> Hold (all states, immortal)
    private final Map<String, Hold>    holdsById     = new ConcurrentHashMap<>();
    // seatId -> userId of confirmed buyer
    private final Map<String, String>  soldSeats     = new ConcurrentHashMap<>();
    // one lock object per seat
    private final Map<String, Object>  seatLocks     = new ConcurrentHashMap<>();
    // one lock object per user
    private final Map<String, Object>  userLocks     = new ConcurrentHashMap<>();
    // per-user count of currently active (non-expired) holds
    private final Map<String, Integer> userHoldCount = new ConcurrentHashMap<>();

    public SeatHoldEngineImpl() {
        this(Instant::now);
    }

    public SeatHoldEngineImpl(Supplier<Instant> clock) {
        this.clock = clock;
    }

    // ---- public API ----

    @Override
    public Result<String> hold(String seatId, String userId, long ttlSeconds) {
        // Acquire user lock first, then seat lock (always this order)
        synchronized (userLockFor(userId)) {
            synchronized (seatLockFor(seatId)) {
                return doHold(seatId, userId, ttlSeconds);
            }
        }
    }

    @Override
    public Result<String> confirm(String holdId) {
        Hold h = holdsById.get(holdId);
        if (h == null) return Result.err("hold not found: " + holdId);

        // confirm doesn't change the user count until inside the seat lock,
        // but we need the user lock to decrement atomically afterwards.
        synchronized (userLockFor(h.getUserId())) {
            synchronized (seatLockFor(h.getSeatId())) {
                return doConfirm(h);
            }
        }
    }

    @Override
    public Result<String> release(String holdId) {
        Hold h = holdsById.get(holdId);
        if (h == null) return Result.err("hold not found: " + holdId);

        synchronized (userLockFor(h.getUserId())) {
            synchronized (seatLockFor(h.getSeatId())) {
                return doRelease(h);
            }
        }
    }

    @Override
    public SeatStatus seatStatus(String seatId) {
        // sold is permanent — safe to read without a lock
        String buyer = soldSeats.get(seatId);
        if (buyer != null) return new SeatStatus.Sold(buyer);

        Hold h = activeHolds.get(seatId);
        if (h != null && h.getState() == Hold.State.ACTIVE && !h.isExpired(clock.get())) {
            return new SeatStatus.Held(h.getUserId(), h.getExpiresAt());
        }
        return new SeatStatus.Free();
    }

    @Override
    public Result<List<String>> holdMany(String userId, List<String> seatIds, long ttlSeconds) {
        if (seatIds == null || seatIds.isEmpty()) return Result.ok(Collections.emptyList());

        List<String> unique = seatIds.stream().distinct().collect(Collectors.toList());
        // Sort seat ids for deadlock-free lock ordering
        List<String> sorted = unique.stream().sorted().collect(Collectors.toList());

        synchronized (userLockFor(userId)) {
            return acquireSeatsAndHold(userId, sorted, unique, ttlSeconds, 0);
        }
    }

    // ---- recursive seat-lock acquisition (avoids arbitrary-depth nested blocks) ----

    private Result<List<String>> acquireSeatsAndHold(
            String userId, List<String> sortedIds, List<String> originalOrder,
            long ttlSeconds, int depth) {

        if (depth == sortedIds.size()) {
            return executeBatch(userId, originalOrder, ttlSeconds);
        }
        synchronized (seatLockFor(sortedIds.get(depth))) {
            return acquireSeatsAndHold(userId, sortedIds, originalOrder, ttlSeconds, depth + 1);
        }
    }

    private Result<List<String>> executeBatch(String userId, List<String> seatIds, long ttlSeconds) {
        Instant now = clock.get();
        int current = userHoldCount.getOrDefault(userId, 0);

        // Count how many seats in the batch are NOT already held by this user
        long newSeats = seatIds.stream().filter(id -> {
            Hold ex = activeHolds.get(id);
            return !(ex != null && ex.getState() == Hold.State.ACTIVE
                    && ex.getUserId().equals(userId) && !ex.isExpired(now));
        }).count();

        if (current + newSeats > MAX_HOLDS_PER_USER) {
            return Result.err("batch would exceed the " + MAX_HOLDS_PER_USER
                    + "-seat limit for user " + userId);
        }

        List<String> acquired = new ArrayList<>();
        for (String seatId : seatIds) {
            Result<String> r = doHold(seatId, userId, ttlSeconds);
            if (r.isErr()) {
                // Roll back every seat we already held in this batch
                for (String hid : acquired) {
                    Hold rb = holdsById.get(hid);
                    if (rb != null) doRelease(rb);
                }
                return Result.err("seat " + seatId + ": " + r.getError());
            }
            acquired.add(r.getValue());
        }
        return Result.ok(acquired);
    }

    // ---- core operations (always called with appropriate locks held) ----

    private Result<String> doHold(String seatId, String userId, long ttlSeconds) {
        Instant now = clock.get();

        if (soldSeats.containsKey(seatId)) return Result.err("seat already sold: " + seatId);

        Hold cur = activeHolds.get(seatId);
        if (cur != null && cur.getState() == Hold.State.ACTIVE) {
            if (!cur.isExpired(now)) {
                // R5 — same user, same seat: idempotent return, no TTL extension (R4)
                if (cur.getUserId().equals(userId)) return Result.ok(cur.getHoldId());
                return Result.err("seat already held by another user: " + seatId);
            }
            // The hold is expired — evict it so the seat is free
            evict(cur);
        }

        // R2 — per-user limit check (user lock is held, so this is race-free)
        int count = userHoldCount.getOrDefault(userId, 0);
        if (count >= MAX_HOLDS_PER_USER) {
            for (Hold h : activeHolds.values()) {
                if (h.getUserId().equals(userId) && h.isExpired(now)) {
                    evict(h);
                }
            }
            count = userHoldCount.getOrDefault(userId, 0);
            if (count >= MAX_HOLDS_PER_USER) {
                return Result.err("user " + userId + " already holds the maximum of "
                        + MAX_HOLDS_PER_USER + " seats");
            }
        }

        String holdId   = UUID.randomUUID().toString();
        Instant expires = now.plusSeconds(ttlSeconds);
        Hold hold = new Hold(holdId, seatId, userId, expires);

        holdsById.put(holdId, hold);
        activeHolds.put(seatId, hold);
        userHoldCount.merge(userId, 1, Integer::sum);

        return Result.ok(holdId);
    }

    /**
     * R3: confirm works past TTL as long as nobody else has since claimed the seat.
     * We check object identity — if activeHolds still points to this exact Hold
     * object we are the rightful owner regardless of the clock.
     */
    private Result<String> doConfirm(Hold h) {
        if (h.getState() == Hold.State.CONFIRMED) return Result.ok("ok"); // idempotent
        if (h.getState() == Hold.State.RELEASED)  return Result.err("hold already released: " + h.getHoldId());

        // Seat must still be "ours" — either active or expired but not retaken
        if (activeHolds.get(h.getSeatId()) != h) {
            return Result.err("seat was reclaimed by another user after hold expired: " + h.getSeatId());
        }

        h.setState(Hold.State.CONFIRMED);
        activeHolds.remove(h.getSeatId());
        soldSeats.put(h.getSeatId(), h.getUserId());
        decrementCount(h.getUserId());

        return Result.ok("ok");
    }

    private Result<String> doRelease(Hold h) {
        if (h.getState() == Hold.State.RELEASED)  return Result.ok("ok"); // idempotent
        if (h.getState() == Hold.State.CONFIRMED) return Result.err("cannot release a confirmed hold: " + h.getHoldId());

        h.setState(Hold.State.RELEASED);
        activeHolds.remove(h.getSeatId(), h);
        decrementCount(h.getUserId());

        return Result.ok("ok");
    }

    /** Evict an expired hold: mark released, remove from active map, fix count. */
    private void evict(Hold h) {
        if (h.getState() == Hold.State.ACTIVE) {
            h.setState(Hold.State.RELEASED);
            activeHolds.remove(h.getSeatId(), h);
            // We hold the user lock for h.getUserId() only when coming from hold()
            // with the same user. For a different user's seat lock acquisition we
            // need to grab the original owner's user lock to decrement safely.
            // Since evict is only called from doHold (inside the user lock of the
            // *requesting* user, not the evicted user), we do a best-effort compute.
            userHoldCount.compute(h.getUserId(), (k, v) -> (v == null || v <= 1) ? null : v - 1);
        }
    }

    private void decrementCount(String userId) {
        userHoldCount.compute(userId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    private Object seatLockFor(String seatId) {
        return seatLocks.computeIfAbsent(seatId, k -> new Object());
    }

    private Object userLockFor(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }
}
