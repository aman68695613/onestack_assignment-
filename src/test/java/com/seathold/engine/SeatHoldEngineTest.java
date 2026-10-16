package com.seathold.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeatHoldEngineTest {

    private FakeClock clock;
    private SeatHoldEngine engine;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(
                Instant.ofEpochSecond(1_000_000)
        );

        engine = new SeatHoldEngineImpl(clock);
    }

    // ===== Basic purchase lifecycle =====

    @Test
    @DisplayName("Hold then confirm sells the seat to the correct user")
    void basicHoldAndConfirm() {
        String holdId = holdSuccessfully("A1", "alice", 60);

        assertHeldBy("A1", "alice");

        Result<String> result = engine.confirm(holdId);

        assertTrue(result.isOk(), () -> "Confirm failed: " + result);
        assertEquals("ok", result.getValue());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    @Test
    @DisplayName("Hold then release makes the seat available to another user")
    void holdThenRelease() {
        String holdId = holdSuccessfully("A1", "alice", 60);

        assertHeldBy("A1", "alice");
        assertTrue(engine.release(holdId).isOk());
        assertFree("A1");

        holdSuccessfully("A1", "bob", 60);
        assertHeldBy("A1", "bob");
    }

    // ===== R1: exclusion and permanent sale =====

    @Test
    @DisplayName("A second user cannot acquire an actively held seat")
    void secondUserCannotHoldHeldSeat() {
        String original = holdSuccessfully("A1", "alice", 60);
        SeatStatus originalStatus = engine.seatStatus("A1");

        Result<String> rejected = engine.hold("A1", "bob", 60);

        assertTrue(rejected.isErr());
        assertEquals(originalStatus, engine.seatStatus("A1"));

        assertEquals(
                original,
                holdSuccessfully("A1", "alice", 60)
        );
    }

    @Test
    @DisplayName("Sold seats remain unavailable even after time advances")
    void cannotHoldSoldSeat() {
        String holdId = holdSuccessfully("A1", "alice", 60);

        assertTrue(engine.confirm(holdId).isOk());

        clock.advanceSeconds(10_000);

        assertTrue(engine.hold("A1", "bob", 60).isErr());
        assertTrue(engine.hold("A1", "alice", 60).isErr());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    // ===== R2: quota and accounting =====

    @Test
    @DisplayName("A user cannot acquire a fifth active seat")
    void userCappedAtFourHolds() {
        holdFourSeats("alice", 60);

        assertTrue(engine.hold("S5", "alice", 60).isErr());
        assertFree("S5");

        for (int i = 1; i <= 4; i++) {
            assertHeldBy("S" + i, "alice");
        }
    }

    @Test
    @DisplayName("Different users have independent quotas")
    void userQuotasAreIndependent() {
        holdFourSeats("alice", 60);

        for (int i = 1; i <= 4; i++) {
            holdSuccessfully("B" + i, "bob", 60);
        }

        assertTrue(engine.hold("A5", "alice", 60).isErr());
        assertTrue(engine.hold("B5", "bob", 60).isErr());
    }

    @Test
    @DisplayName("Release frees exactly one quota slot")
    void releaseFreesSeatSlot() {
        List<String> ids = holdFourSeats("alice", 60);

        assertTrue(engine.release(ids.get(0)).isOk());

        holdSuccessfully("S5", "alice", 60);
        assertTrue(engine.hold("S6", "alice", 60).isErr());
    }

    @Test
    @DisplayName("Confirmation frees exactly one quota slot")
    void confirmFreesSeatSlot() {
        List<String> ids = holdFourSeats("alice", 60);

        assertTrue(engine.confirm(ids.get(0)).isOk());

        holdSuccessfully("S5", "alice", 60);
        assertTrue(engine.hold("S6", "alice", 60).isErr());
    }

    @Test
    @DisplayName("Repeated release does not decrement quota twice")
    void repeatedReleaseDoesNotCorruptQuota() {
        List<String> ids = holdFourSeats("alice", 60);

        assertTrue(engine.release(ids.get(0)).isOk());
        assertTrue(engine.release(ids.get(0)).isOk());

        holdSuccessfully("S5", "alice", 60);

        assertTrue(
                engine.hold("S6", "alice", 60).isErr(),
                "Repeated release must not create an extra quota slot"
        );
    }

    @Test
    @DisplayName("Repeated confirmation does not decrement quota twice")
    void repeatedConfirmDoesNotCorruptQuota() {
        List<String> ids = holdFourSeats("alice", 60);

        assertTrue(engine.confirm(ids.get(0)).isOk());
        assertTrue(engine.confirm(ids.get(0)).isOk());

        holdSuccessfully("S5", "alice", 60);

        assertTrue(
                engine.hold("S6", "alice", 60).isErr(),
                "Repeated confirmation must not create an extra quota slot"
        );

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("S1")
        );
    }

    @Test
    @DisplayName("Expired holds do not block a new single-seat acquisition")
    void expiredHoldsDoNotBlockNewSingleHold() {
        holdFourSeats("alice", 1);
        clock.advanceSeconds(2);

        holdSuccessfully("S5", "alice", 60);
        assertHeldBy("S5", "alice");
    }

    // ===== R3: expiry and late confirmation =====

    @Test
    @DisplayName("An unretaken expired hold remains confirmable")
    void expiredHoldCanBeConfirmed() {
        String holdId = holdSuccessfully("A1", "alice", 30);

        clock.advanceSeconds(31);

        assertFree("A1");

        Result<String> result = engine.confirm(holdId);

        assertTrue(
                result.isOk(),
                () -> "Late confirmation failed: " + result
        );

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    @Test
    @DisplayName("Repeated status reads do not revoke late confirmation")
    void statusReadsDoNotInvalidateExpiredHold() {
        String holdId = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        for (int i = 0; i < 5; i++) {
            assertFree("A1");
        }

        assertTrue(engine.confirm(holdId).isOk());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    @Test
    @DisplayName("An expired hold cannot confirm after successful takeover")
    void expiredHoldBlockedWhenSeatRetaken() {
        String aliceHold = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        String bobHold = holdSuccessfully("A1", "bob", 60);

        assertTrue(engine.confirm(aliceHold).isErr());
        assertHeldBy("A1", "bob");

        assertTrue(engine.confirm(bobHold).isOk());

        assertEquals(
                new SeatStatus.Sold("bob"),
                engine.seatStatus("A1")
        );
    }

    @Test
    @DisplayName("Replacement release does not revive the old hold")
    void replacementReleaseDoesNotReviveOldHold() {
        String aliceHold = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        String bobHold = holdSuccessfully("A1", "bob", 60);

        assertTrue(engine.release(bobHold).isOk());
        assertFree("A1");

        assertTrue(
                engine.confirm(aliceHold).isErr(),
                "Successful takeover permanently invalidates the old ID"
        );

        assertFree("A1");
    }

    @Test
    @DisplayName("Releasing an old hold cannot release its replacement")
    void oldReleaseCannotRemoveReplacement() {
        String aliceHold = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        String bobHold = holdSuccessfully("A1", "bob", 60);

        assertTrue(engine.release(aliceHold).isOk());
        assertHeldBy("A1", "bob");

        assertTrue(engine.confirm(bobHold).isOk());

        assertEquals(
                new SeatStatus.Sold("bob"),
                engine.seatStatus("A1")
        );
    }

    @Test
    @DisplayName("Explicit release permanently prevents late confirmation")
    void releasedExpiredHoldCannotConfirm() {
        String holdId = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        assertTrue(engine.release(holdId).isOk());
        assertTrue(engine.confirm(holdId).isErr());
        assertFree("A1");
    }

    @Test
    @DisplayName("Quota cleanup must preserve unretaken late-confirm rights")
    void quotaCleanupMustNotDestroyLateConfirmation() {
        List<String> ids = holdFourSeats("alice", 1);

        clock.advanceSeconds(2);

        holdSuccessfully("S5", "alice", 60);

        assertTrue(
                engine.confirm(ids.get(0)).isOk(),
                "S1 was neither explicitly released nor successfully retaken"
        );

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("S1")
        );

        assertHeldBy("S5", "alice");
    }

    @Test
    @DisplayName("A rejected takeover must not invalidate the old holder")
    void rejectedTakeoverPreservesOldLateConfirmation() {
        String aliceHold = holdSuccessfully("A1", "alice", 1);

        for (int i = 1; i <= 4; i++) {
            holdSuccessfully("B" + i, "bob", 60);
        }

        clock.advanceSeconds(2);

        assertTrue(
                engine.hold("A1", "bob", 60).isErr(),
                "Bob already holds four active seats"
        );

        assertTrue(
                engine.confirm(aliceHold).isOk(),
                "Bob never successfully acquired A1"
        );

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    // ===== R4 and R5: immutable expiry and active retries =====

    @Test
    @DisplayName("An active retry preserves the original hold ID")
    void idempotentRetryReturnsSameHoldId() {
        String first = holdSuccessfully("A1", "alice", 60);
        String second = holdSuccessfully("A1", "alice", 60);

        assertEquals(first, second);
    }

    @Test
    @DisplayName("An active retry preserves the original expiry")
    void idempotentHoldDoesNotExtendTtl() {
        String first = holdSuccessfully("A1", "alice", 30);

        SeatStatus.Held original = assertHeldBy("A1", "alice");

        clock.advanceSeconds(20);

        String retry = holdSuccessfully("A1", "alice", 9999);

        assertEquals(first, retry);
        assertEquals(original, engine.seatStatus("A1"));

        clock.advanceSeconds(11);

        assertFree("A1");
    }

    @Test
    @DisplayName("An idempotent retry succeeds at the four-seat quota")
    void retryAtQuotaDoesNotConsumeAnotherSlot() {
        List<String> ids = holdFourSeats("alice", 60);

        SeatStatus original = engine.seatStatus("S1");

        String retry = holdSuccessfully("S1", "alice", 999);

        assertEquals(ids.get(0), retry);
        assertEquals(original, engine.seatStatus("S1"));

        assertTrue(engine.hold("S5", "alice", 60).isErr());
    }

    @Test
    @DisplayName("Same-user reacquisition after expiry creates a new hold")
    void sameUserReacquiresExpiredSeatWithNewId() {
        String old = holdSuccessfully("A1", "alice", 1);

        clock.advanceSeconds(2);

        String fresh = holdSuccessfully("A1", "alice", 60);

        assertNotEquals(old, fresh);
        assertTrue(engine.confirm(old).isErr());
        assertTrue(engine.confirm(fresh).isOk());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A1")
        );
    }

    // ===== R6: successful batches =====

    @Test
    @DisplayName("A successful batch owns every requested seat")
    void holdManyAllAvailable() {
        List<String> seats = List.of("A", "B", "C");

        Result<List<String>> result =
                engine.holdMany("alice", seats, 60);

        assertTrue(result.isOk(), () -> "Batch failed: " + result);
        assertEquals(3, result.getValue().size());

        for (int i = 0; i < seats.size(); i++) {
            String seat = seats.get(i);

            assertHeldBy(seat, "alice");

            assertEquals(
                    result.getValue().get(i),
                    holdSuccessfully(seat, "alice", 60)
            );
        }
    }

    @Test
    @DisplayName("Duplicate batch inputs count once and preserve first order")
    void holdManyDeduplicatesSeats() {
        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("B", "A", "B"),
                60
        );

        assertTrue(result.isOk(), () -> "Batch failed: " + result);
        assertEquals(2, result.getValue().size());

        assertEquals(
                holdSuccessfully("B", "alice", 60),
                result.getValue().get(0)
        );

        assertEquals(
                holdSuccessfully("A", "alice", 60),
                result.getValue().get(1)
        );

        holdSuccessfully("C", "alice", 60);
        holdSuccessfully("D", "alice", 60);

        assertTrue(engine.hold("E", "alice", 60).isErr());
    }

    @Test
    @DisplayName("Batch counts existing active holds as zero net additions")
    void holdManyIdempotentSeatCountedOnce() {
        String original = holdSuccessfully("A", "alice", 60);

        holdSuccessfully("B", "alice", 60);
        holdSuccessfully("C", "alice", 60);

        SeatStatus originalStatus = engine.seatStatus("A");

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("A", "D"),
                999
        );

        assertTrue(result.isOk(), () -> "Batch failed: " + result);
        assertEquals(original, result.getValue().get(0));
        assertEquals(originalStatus, engine.seatStatus("A"));
        assertHeldBy("D", "alice");

        assertTrue(engine.hold("E", "alice", 60).isErr());
    }

    @Test
    @DisplayName("A batch of existing holds succeeds at the quota")
    void holdManyExistingSeatsSucceedAtQuota() {
        List<String> ids = holdFourSeats("alice", 60);

        SeatStatus firstStatus = engine.seatStatus("S1");
        SeatStatus secondStatus = engine.seatStatus("S2");

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("S1", "S2", "S1"),
                999
        );

        assertTrue(result.isOk(), () -> "Batch failed: " + result);

        assertEquals(
                List.of(ids.get(0), ids.get(1)),
                result.getValue()
        );

        assertEquals(firstStatus, engine.seatStatus("S1"));
        assertEquals(secondStatus, engine.seatStatus("S2"));

        assertTrue(engine.hold("S5", "alice", 60).isErr());
    }

    @Test
    @DisplayName("An empty batch succeeds without changing existing state")
    void emptyBatchHasNoEffect() {
        String original = holdSuccessfully("A", "alice", 60);
        SeatStatus before = engine.seatStatus("A");

        Result<List<String>> result =
                engine.holdMany("alice", List.of(), 60);

        assertTrue(result.isOk());
        assertTrue(result.getValue().isEmpty());

        assertEquals(before, engine.seatStatus("A"));
        assertEquals(
                original,
                holdSuccessfully("A", "alice", 60)
        );
    }

    // ===== R6: rejected batches and atomicity =====

    @Test
    @DisplayName("A conflicting batch releases only its newly created seats")
    void holdManyRollbackOnConflict() {
        String bobHold = holdSuccessfully("C", "bob", 60);

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("A", "B", "C"),
                60
        );

        assertTrue(result.isErr());

        assertFree("A");
        assertFree("B");
        assertHeldBy("C", "bob");

        assertEquals(
                bobHold,
                holdSuccessfully("C", "bob", 60)
        );
    }

    @Test
    @DisplayName("Failed batch must preserve a pre-existing hold")
    void failedBatchPreservesExistingHold() {
        String aliceHold = holdSuccessfully("A", "alice", 60);
        SeatStatus originalStatus = engine.seatStatus("A");

        holdSuccessfully("C", "bob", 60);

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("A", "B", "C"),
                999
        );

        assertTrue(result.isErr());

        assertEquals(
                originalStatus,
                engine.seatStatus("A"),
                "A existed before the failed batch"
        );

        assertFree("B");
        assertHeldBy("C", "bob");

        assertEquals(
                aliceHold,
                holdSuccessfully("A", "alice", 60)
        );

        assertTrue(engine.confirm(aliceHold).isOk());
    }

    @Test
    @DisplayName("A failed batch does not leak quota")
    void failedBatchDoesNotLeakQuota() {
        holdSuccessfully("C", "bob", 60);

        assertTrue(
                engine.holdMany(
                        "alice",
                        List.of("A", "B", "C"),
                        60
                ).isErr()
        );

        for (String seat : List.of("D", "E", "F", "G")) {
            holdSuccessfully(seat, "alice", 60);
        }

        assertTrue(engine.hold("H", "alice", 60).isErr());
    }

    @Test
    @DisplayName("Over-quota batch leaves both requested seats free")
    void holdManyRespectsUserLimit() {
        holdSuccessfully("A", "alice", 60);
        holdSuccessfully("B", "alice", 60);
        holdSuccessfully("C", "alice", 60);

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("D", "E"),
                60
        );

        assertTrue(result.isErr());

        assertFree("D");
        assertFree("E");

        assertHeldBy("A", "alice");
        assertHeldBy("B", "alice");
        assertHeldBy("C", "alice");

        holdSuccessfully("F", "alice", 60);
        assertTrue(engine.hold("G", "alice", 60).isErr());
    }

    @Test
    @DisplayName("A batch of more than four unique seats has no effect")
    void oversizedBatchLeavesAllSeatsFree() {
        List<String> seats = List.of("A", "B", "C", "D", "E");

        Result<List<String>> result =
                engine.holdMany("alice", seats, 60);

        assertTrue(result.isErr());

        for (String seat : seats) {
            assertFree(seat);
        }
    }

    @Test
    @DisplayName("A sold-seat conflict leaves other requested seats free")
    void holdManyFailsOnSoldSeat() {
        String soldHold = holdSuccessfully("C", "bob", 60);

        assertTrue(engine.confirm(soldHold).isOk());

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("A", "B", "C"),
                60
        );

        assertTrue(result.isErr());

        assertFree("A");
        assertFree("B");

        assertEquals(
                new SeatStatus.Sold("bob"),
                engine.seatStatus("C")
        );
    }

    @Test
    @DisplayName("Expired holds do not consume quota for a new batch")
    void expiredHoldsDoNotBlockNewBatch() {
        holdFourSeats("alice", 1);

        clock.advanceSeconds(2);

        Result<List<String>> result = engine.holdMany(
                "alice",
                List.of("A", "B"),
                60
        );

        assertTrue(
                result.isOk(),
                () -> "Expired holds must not consume quota: " + result
        );

        assertHeldBy("A", "alice");
        assertHeldBy("B", "alice");
    }

    @Test
    @DisplayName("A failed batch preserves an expired prior entitlement")
    void failedBatchPreservesExpiredLateConfirmation() {
        String aliceHold = holdSuccessfully("A", "alice", 1);

        holdSuccessfully("C", "charlie", 60);

        clock.advanceSeconds(2);

        Result<List<String>> result = engine.holdMany(
                "bob",
                List.of("A", "B", "C"),
                60
        );

        assertTrue(result.isErr());

        assertFree("B");
        assertHeldBy("C", "charlie");

        assertTrue(
                engine.confirm(aliceHold).isOk(),
                "A failed all-or-nothing batch must not revoke Alice's prior entitlement"
        );

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A")
        );
    }

    // ===== Boundaries and terminal-state behavior =====

    @Test
    @DisplayName("Unknown seat is free")
    void unknownSeatIsFree() {
        assertFree("unknown-seat");
    }

    @Test
    @DisplayName("Unknown hold cannot be confirmed")
    void confirmUnknownHoldId() {
        assertTrue(engine.confirm("unknown-hold").isErr());
    }

    @Test
    @DisplayName("Unknown hold cannot be released")
    void releaseUnknownHoldId() {
        assertTrue(engine.release("unknown-hold").isErr());
    }

    @Test
    @DisplayName("A confirmed hold cannot be released")
    void cannotReleaseConfirmedHold() {
        String holdId = holdSuccessfully("A", "alice", 60);

        assertTrue(engine.confirm(holdId).isOk());
        assertTrue(engine.release(holdId).isErr());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A")
        );
    }

    @Test
    @DisplayName("Confirmation is idempotent")
    void confirmIdempotent() {
        String holdId = holdSuccessfully("A", "alice", 60);

        assertTrue(engine.confirm(holdId).isOk());

        Result<String> repeated = engine.confirm(holdId);

        assertTrue(repeated.isOk());
        assertEquals("ok", repeated.getValue());

        assertEquals(
                new SeatStatus.Sold("alice"),
                engine.seatStatus("A")
        );
    }

    @Test
    @DisplayName("Release is idempotent and prevents confirmation")
    void releaseIdempotent() {
        String holdId = holdSuccessfully("A", "alice", 60);

        assertTrue(engine.release(holdId).isOk());
        assertTrue(engine.release(holdId).isOk());
        assertTrue(engine.confirm(holdId).isErr());

        assertFree("A");
    }

    @Test
    @DisplayName("Held status contains the correct owner and expiry")
    void heldStatusDetails() {
        Instant before = clock.get();

        holdSuccessfully("A", "alice", 120);

        assertEquals(
                new SeatStatus.Held(
                        "alice",
                        before.plusSeconds(120)
                ),
                engine.seatStatus("A")
        );
    }

    @Test
    @DisplayName("Expiry boundary preserves the current strictly-after policy")
    void exactExpiryBoundary() {
        holdSuccessfully("A", "alice", 1);

        clock.advanceMillis(999);
        assertHeldBy("A", "alice");

        clock.advanceMillis(1);

        /*
         * Current policy: now == expiresAt is still active.
         * Change this test if the documented policy changes to >=.
         */
        assertHeldBy("A", "alice");
        assertTrue(engine.hold("A", "bob", 60).isErr());

        clock.advanceMillis(1);

        assertFree("A");
        holdSuccessfully("A", "bob", 60);
        assertHeldBy("A", "bob");
    }

    // ===== Helpers =====

    private String holdSuccessfully(
            String seatId,
            String userId,
            long ttlSeconds
    ) {
        Result<String> result = engine.hold(
                seatId,
                userId,
                ttlSeconds
        );

        assertTrue(
                result.isOk(),
                () -> "Expected successful hold for seat "
                        + seatId + ", user " + userId
                        + ", but got " + result
        );

        assertNotNull(result.getValue());

        return result.getValue();
    }

    private List<String> holdFourSeats(
            String userId,
            long ttlSeconds
    ) {
        List<String> ids = new ArrayList<>();

        for (int i = 1; i <= 4; i++) {
            ids.add(
                    holdSuccessfully(
                            "S" + i,
                            userId,
                            ttlSeconds
                    )
            );
        }

        return ids;
    }

    private SeatStatus.Held assertHeldBy(
            String seatId,
            String userId
    ) {
        SeatStatus.Held held = assertInstanceOf(
                SeatStatus.Held.class,
                engine.seatStatus(seatId),
                "Expected HELD status for " + seatId
        );

        assertEquals(
                userId,
                held.userId(),
                "Incorrect owner for " + seatId
        );

        return held;
    }

    private void assertFree(String seatId) {
        assertInstanceOf(
                SeatStatus.Free.class,
                engine.seatStatus(seatId),
                "Expected FREE status for " + seatId
        );
    }
}