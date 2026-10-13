package com.seathold.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering all business rules and edge cases.
 * The clock is always fake so tests finish instantly.
 */
class SeatHoldEngineTest {

    private FakeClock clock;
    private SeatHoldEngine engine;

    @BeforeEach
    void setUp() {
        clock  = new FakeClock(Instant.ofEpochSecond(1_000_000));
        engine = new SeatHoldEngineImpl(clock);
    }

    // ===== Basic happy path =====

    @Test
    @DisplayName("hold → confirm — basic purchase flow works")
    void basicHoldAndConfirm() {
        Result<String> r = engine.hold("A1", "alice", 60);
        assertTrue(r.isOk(), "hold should succeed");

        Result<String> confirm = engine.confirm(r.getValue());
        assertTrue(confirm.isOk(), "confirm should succeed");
        assertEquals("ok", confirm.getValue());

        assertTrue(engine.seatStatus("A1") instanceof SeatStatus.Sold);
    }

    @Test
    @DisplayName("hold → release — seat goes back to free")
    void holdThenRelease() {
        Result<String> r = engine.hold("B2", "bob", 60);
        assertTrue(r.isOk());

        assertTrue(engine.seatStatus("B2") instanceof SeatStatus.Held);

        Result<String> rel = engine.release(r.getValue());
        assertTrue(rel.isOk());
        assertTrue(engine.seatStatus("B2") instanceof SeatStatus.Free);
    }

    // ===== R1: at most one holder; sold only once =====

    @Test
    @DisplayName("R1 — second user cannot hold an already-held seat")
    void secondUserCannotHoldHeldSeat() {
        engine.hold("C3", "alice", 60);
        Result<String> second = engine.hold("C3", "bob", 60);
        assertTrue(second.isErr());
    }

    @Test
    @DisplayName("R1 — cannot hold a sold seat")
    void cannotHoldSoldSeat() {
        String holdId = engine.hold("D4", "alice", 60).getValue();
        engine.confirm(holdId);
        Result<String> late = engine.hold("D4", "bob", 60);
        assertTrue(late.isErr());
    }

    // ===== R2: user holds at most 4 seats =====

    @Test
    @DisplayName("R2 — user is blocked after 4 holds")
    void userCappedAtFourHolds() {
        for (int i = 1; i <= 4; i++) {
            assertTrue(engine.hold("seat-" + i, "alice", 60).isOk());
        }
        Result<String> fifth = engine.hold("seat-5", "alice", 60);
        assertTrue(fifth.isErr(), "fifth hold must be rejected");
    }

    @Test
    @DisplayName("R2 — releasing a hold frees up a slot")
    void releaseFreesSeatSlot() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(engine.hold("seat-" + i, "alice", 60).getValue());
        }
        engine.release(ids.get(0)); // free up one slot
        assertTrue(engine.hold("seat-5", "alice", 60).isOk());
    }

    @Test
    @DisplayName("R2 — confirming a hold also frees up the slot")
    void confirmFreesSeatSlot() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(engine.hold("seat-" + i, "alice", 60).getValue());
        }
        engine.confirm(ids.get(0));
        assertTrue(engine.hold("seat-5", "alice", 60).isOk());
    }

    // ===== R3: expired hold confirmable if nobody else took seat =====

    @Test
    @DisplayName("R3 — expired hold can still be confirmed when seat is still free")
    void expiredHoldCanBeConfirmed() {
        String holdId = engine.hold("E5", "alice", 30).getValue();

        // Move time past the TTL
        clock.advanceSeconds(60);

        // Seat should appear free now (lazy expiry in status check)
        assertTrue(engine.seatStatus("E5") instanceof SeatStatus.Free);

        // But Alice can still confirm — nobody else took it
        Result<String> conf = engine.confirm(holdId);
        assertTrue(conf.isOk(), "should confirm even after TTL if seat not retaken: " + conf.getError());
        assertTrue(engine.seatStatus("E5") instanceof SeatStatus.Sold);
    }

    @Test
    @DisplayName("R3 — expired hold cannot be confirmed if another user grabbed the seat")
    void expiredHoldBlockedWhenSeatRetaken() {
        String aliceHold = engine.hold("F6", "alice", 30).getValue();
        clock.advanceSeconds(60); // alice's hold expires

        // Bob grabs the now-free seat
        engine.hold("F6", "bob", 60);

        // Alice tries to confirm her old hold — should fail
        Result<String> conf = engine.confirm(aliceHold);
        assertTrue(conf.isErr(), "should fail because bob now holds it");
    }

    // ===== R4: holds are never extended =====

    @Test
    @DisplayName("R4 — idempotent hold does not extend the TTL")
    void idempotentHoldDoesNotExtendTtl() {
        String h1 = engine.hold("G7", "alice", 30).getValue();

        // Advance time so we're close to expiry
        clock.advanceSeconds(20);

        // Same user calls hold again with a longer TTL
        String h2 = engine.hold("G7", "alice", 9999).getValue();

        // Must be the same hold-id (R5), and the original TTL still applies
        assertEquals(h1, h2, "idempotent hold must return the same hold-id");

        // Advance past the original 30s TTL
        clock.advanceSeconds(15); // total 35s elapsed

        // The hold should now be expired
        assertTrue(engine.seatStatus("G7") instanceof SeatStatus.Free,
                "seat should be free after original TTL, not the new 9999s one");
    }

    // ===== R5: idempotent retry for same user + same seat =====

    @Test
    @DisplayName("R5 — same user calling hold on same seat returns the same hold-id")
    void idempotentRetryReturnsSameHoldId() {
        String h1 = engine.hold("H8", "alice", 60).getValue();
        String h2 = engine.hold("H8", "alice", 60).getValue();
        assertEquals(h1, h2);
    }

    // ===== R6: holdMany is all-or-nothing =====

    @Test
    @DisplayName("R6 — holdMany succeeds when all seats are available")
    void holdManyAllAvailable() {
        Result<List<String>> r = engine.holdMany("alice", List.of("M1", "M2", "M3"), 60);
        assertTrue(r.isOk());
        assertEquals(3, r.getValue().size());
    }

    @Test
    @DisplayName("R6 — holdMany rolls back when one seat is already held")
    void holdManyRollbackOnConflict() {
        engine.hold("N1", "bob", 60); // block one seat

        Result<List<String>> r = engine.holdMany("alice", List.of("N2", "N1", "N3"), 60);
        assertTrue(r.isErr(), "should fail because N1 is taken");

        // N2 and N3 must have been rolled back
        assertTrue(engine.seatStatus("N2") instanceof SeatStatus.Free);
        assertTrue(engine.seatStatus("N3") instanceof SeatStatus.Free);
    }

    @Test
    @DisplayName("R6 — holdMany respects the per-user limit (R2)")
    void holdManyRespectsUserLimit() {
        engine.hold("P1", "alice", 60);
        engine.hold("P2", "alice", 60);
        engine.hold("P3", "alice", 60);
        // Alice now holds 3. Requesting 2 more would exceed limit of 4.
        Result<List<String>> r = engine.holdMany("alice", List.of("P4", "P5"), 60);
        assertTrue(r.isErr(), "batch exceeds per-user limit");
    }

    @Test
    @DisplayName("R6 — holdMany counts idempotent re-holds correctly (R2+R5)")
    void holdManyIdempotentSeatCountedOnce() {
        // Alice holds 3 seats already
        engine.hold("Q1", "alice", 60);
        engine.hold("Q2", "alice", 60);
        engine.hold("Q3", "alice", 60);
        // holdMany with Q1 (already hers) + one new seat = net +1 → total 4, fine
        Result<List<String>> r = engine.holdMany("alice", List.of("Q1", "Q4"), 60);
        assertTrue(r.isOk(), "should succeed because Q1 is already Alice's: " + (r.isErr() ? r.getError() : ""));
    }

    // ===== Boundary / misc =====

    @Test
    @DisplayName("seatStatus returns Free for unknown seat")
    void unknownSeatIsFree() {
        assertTrue(engine.seatStatus("nonexistent") instanceof SeatStatus.Free);
    }

    @Test
    @DisplayName("confirm on unknown hold-id returns error")
    void confirmUnknownHoldId() {
        Result<String> r = engine.confirm("does-not-exist");
        assertTrue(r.isErr());
    }

    @Test
    @DisplayName("release on unknown hold-id returns error")
    void releaseUnknownHoldId() {
        Result<String> r = engine.release("does-not-exist");
        assertTrue(r.isErr());
    }

    @Test
    @DisplayName("cannot release a confirmed hold")
    void cannotReleaseConfirmedHold() {
        String holdId = engine.hold("Z1", "alice", 60).getValue();
        engine.confirm(holdId);
        Result<String> r = engine.release(holdId);
        assertTrue(r.isErr());
    }

    @Test
    @DisplayName("confirm is idempotent for an already-confirmed hold")
    void confirmIdempotent() {
        String holdId = engine.hold("Z2", "alice", 60).getValue();
        engine.confirm(holdId);
        Result<String> second = engine.confirm(holdId);
        assertTrue(second.isOk(), "second confirm on same hold should be ok");
    }

    @Test
    @DisplayName("release is idempotent")
    void releaseIdempotent() {
        String holdId = engine.hold("Z3", "alice", 60).getValue();
        engine.release(holdId);
        Result<String> second = engine.release(holdId);
        assertTrue(second.isOk());
    }

    @Test
    @DisplayName("Held status includes correct userId and expiry")
    void heldStatusDetails() {
        Instant before = clock.get();
        engine.hold("Z4", "alice", 120);
        SeatStatus s = engine.seatStatus("Z4");
        assertInstanceOf(SeatStatus.Held.class, s);
        SeatStatus.Held held = (SeatStatus.Held) s;
        assertEquals("alice", held.userId());
        assertEquals(before.plusSeconds(120), held.expiresAt());
    }
}
