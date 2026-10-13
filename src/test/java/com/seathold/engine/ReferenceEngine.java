package com.seathold.engine;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deliberately simple, single-threaded reference implementation of the same
 * spec. It makes no attempt to be concurrent; its job is to be obviously
 * correct so the model-based test can compare it against the real engine.
 *
 * Not thread-safe — only used from ConcurrencyStressTest's single-threaded
 * model-based scenario.
 */
class ReferenceEngine implements SeatHoldEngine {

    private final Supplier<Instant> clock;
    private final Map<String, Hold>   holds     = new HashMap<>();
    private final Map<String, Hold>   bySeatId  = new HashMap<>();
    private final Map<String, String> sold      = new HashMap<>();

    ReferenceEngine(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public Result<String> hold(String seatId, String userId, long ttlSeconds) {
        Instant now = clock.get();
        if (sold.containsKey(seatId)) return Result.err("sold");

        Hold cur = bySeatId.get(seatId);
        if (cur != null && cur.getState() == Hold.State.ACTIVE && !cur.isExpired(now)) {
            if (cur.getUserId().equals(userId)) return Result.ok(cur.getHoldId());
            return Result.err("held");
        }
        if (cur != null && cur.getState() == Hold.State.ACTIVE && cur.isExpired(now)) {
            cur.setState(Hold.State.RELEASED);
            bySeatId.remove(seatId);
            decrementCount(cur.getUserId());
        }

        long cnt = holds.values().stream()
                .filter(h -> h.getUserId().equals(userId) && h.getState() == Hold.State.ACTIVE && !h.isExpired(now))
                .count();
        if (cnt >= 4) return Result.err("limit");

        String id = UUID.randomUUID().toString();
        Hold h = new Hold(id, seatId, userId, now.plusSeconds(ttlSeconds));
        holds.put(id, h);
        bySeatId.put(seatId, h);
        return Result.ok(id);
    }

    @Override
    public Result<String> confirm(String holdId) {
        Hold h = holds.get(holdId);
        if (h == null) return Result.err("not found");
        if (h.getState() == Hold.State.CONFIRMED) return Result.ok("ok");
        if (h.getState() == Hold.State.RELEASED) return Result.err("released");
        if (bySeatId.get(h.getSeatId()) != h) return Result.err("taken");
        h.setState(Hold.State.CONFIRMED);
        bySeatId.remove(h.getSeatId());
        sold.put(h.getSeatId(), h.getUserId());
        decrementCount(h.getUserId());
        return Result.ok("ok");
    }

    @Override
    public Result<String> release(String holdId) {
        Hold h = holds.get(holdId);
        if (h == null) return Result.err("not found");
        if (h.getState() == Hold.State.RELEASED) return Result.ok("ok");
        if (h.getState() == Hold.State.CONFIRMED) return Result.err("confirmed");
        h.setState(Hold.State.RELEASED);
        bySeatId.remove(h.getSeatId(), h);
        decrementCount(h.getUserId());
        return Result.ok("ok");
    }

    @Override
    public SeatStatus seatStatus(String seatId) {
        if (sold.containsKey(seatId)) return new SeatStatus.Sold(sold.get(seatId));
        Hold h = bySeatId.get(seatId);
        if (h != null && h.getState() == Hold.State.ACTIVE && !h.isExpired(clock.get())) {
            return new SeatStatus.Held(h.getUserId(), h.getExpiresAt());
        }
        return new SeatStatus.Free();
    }

    @Override
    public Result<List<String>> holdMany(String userId, List<String> seatIds, long ttlSeconds) {
        return Result.err("not used in model test");
    }

    private void decrementCount(String userId) {
        // state is fully derived from holds map, so nothing to do here
    }
}
