package com.seathold.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {

    private static final int THREADS = 16;
    private static final int SEAT_COUNT = 12;
    private static final int USER_COUNT = 4;
    private static final int EPISODES = 20;
    private static final int OPERATIONS_PER_THREAD = 80;
    private static final long TTL_SECONDS = 3;

    private record TrackedHold(
            String holdId,
            String seatId,
            String userId
    ) {}

    private record HoldPair(
            String engineId,
            String referenceId
    ) {}

    @Test
    @DisplayName("Stress: concurrent operations preserve observable invariants")
    void stressTest() throws Exception {
        long seed = Long.getLong("stress.seed", 424242L);
        System.out.println("[ConcurrencyStressTest] seed = " + seed);

        /*
         * Fresh inventory in each episode prevents the test from becoming
         * mostly rejection checks after every seat has been sold.
         */
        for (int episode = 0; episode < EPISODES; episode++) {
            final int episodeId = episode;

            FakeClock clock = new FakeClock(Instant.EPOCH);
            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

            Map<String, TrackedHold> holds = new ConcurrentHashMap<>();
            Map<String, TrackedHold> salesBySeat = new ConcurrentHashMap<>();

            List<String> seats = createSeats(SEAT_COUNT);
            List<Callable<Void>> actions = new ArrayList<>();

            for (int thread = 0; thread < THREADS; thread++) {
                final int threadId = thread;

                actions.add(() -> {
                    Random random = new Random(
                            seed ^ ((long) episodeId << 32) ^ threadId
                    );

                    for (int operation = 0;
                         operation < OPERATIONS_PER_THREAD;
                         operation++) {

                        String seat = seats.get(
                                random.nextInt(seats.size())
                        );

                        String user = "user-" + random.nextInt(USER_COUNT);

                        switch (random.nextInt(7)) {
                            case 0, 1 -> {
                                Result<String> result = engine.hold(
                                        seat,
                                        user,
                                        TTL_SECONDS
                                );

                                if (result.isOk()) {
                                    rememberHold(
                                            holds,
                                            new TrackedHold(
                                                    result.getValue(),
                                                    seat,
                                                    user
                                            )
                                    );
                                }
                            }

                            case 2 -> {
                                String secondSeat = seats.get(
                                        random.nextInt(seats.size())
                                );

                                List<String> requested = List.of(
                                        seat,
                                        secondSeat
                                );

                                List<String> unique = new ArrayList<>(
                                        new LinkedHashSet<>(requested)
                                );

                                Result<List<String>> result =
                                        engine.holdMany(
                                                user,
                                                requested,
                                                TTL_SECONDS
                                        );

                                if (result.isOk()) {
                                    assertEquals(
                                            unique.size(),
                                            result.getValue().size(),
                                            "Batch result must contain one ID per unique seat"
                                    );

                                    for (int i = 0; i < unique.size(); i++) {
                                        rememberHold(
                                                holds,
                                                new TrackedHold(
                                                        result.getValue().get(i),
                                                        unique.get(i),
                                                        user
                                                )
                                        );
                                    }
                                }
                            }

                            case 3 -> {
                                TrackedHold hold = pickRandomHold(
                                        holds,
                                        random
                                );

                                if (hold != null) {
                                    Result<String> result =
                                            engine.confirm(hold.holdId());

                                    if (result.isOk()) {
                                        TrackedHold previous =
                                                salesBySeat.putIfAbsent(
                                                        hold.seatId(),
                                                        hold
                                                );

                                        /*
                                         * Reconfirming the SAME hold is legal.
                                         * Selling through a DIFFERENT hold is not.
                                         */
                                        if (previous != null) {
                                            assertEquals(
                                                    previous,
                                                    hold,
                                                    "Different holds confirmed for seat "
                                                            + hold.seatId()
                                            );
                                        }
                                    }
                                }
                            }

                            case 4 -> {
                                TrackedHold hold = pickRandomHold(
                                        holds,
                                        random
                                );

                                if (hold != null) {
                                    engine.release(hold.holdId());
                                }
                            }

                            case 5 -> clock.advanceMillis(
                                    1 + random.nextInt(1000)
                            );

                            case 6 -> assertNotNull(
                                    engine.seatStatus(seat)
                            );

                            default -> throw new AssertionError(
                                    "Unexpected action"
                            );
                        }
                    }

                    return null;
                });
            }

            try {
                runConcurrently(actions);

                /*
                 * All workers, including clock mutations, have finished.
                 * These are quiescent checks, not inconsistent live scans.
                 */
                Map<String, Integer> activeByUser = new HashMap<>();

                for (String seat : seats) {
                    SeatStatus status = engine.seatStatus(seat);

                    if (status instanceof SeatStatus.Held held) {
                        assertNotNull(held.userId());

                        assertFalse(
                                clock.get().isAfter(held.expiresAt()),
                                "Expired hold must not be reported as HELD"
                        );

                        activeByUser.merge(
                                held.userId(),
                                1,
                                Integer::sum
                        );
                    }

                    if (status instanceof SeatStatus.Sold sold) {
                        TrackedHold recordedSale = salesBySeat.get(seat);

                        assertNotNull(
                                recordedSale,
                                "SOLD seat has no successful confirmation: "
                                        + seat
                        );

                        assertEquals(
                                recordedSale.userId(),
                                sold.userId(),
                                "Sale owner differs from confirmed hold owner"
                        );
                    }
                }

                activeByUser.forEach((user, count) ->
                        assertTrue(
                                count <= 4,
                                user + " has " + count + " active holds"
                        )
                );

                for (TrackedHold sale : salesBySeat.values()) {
                    assertEquals(
                            new SeatStatus.Sold(sale.userId()),
                            engine.seatStatus(sale.seatId())
                    );

                    for (TrackedHold other : holds.values()) {
                        if (other.seatId().equals(sale.seatId())
                                && !other.holdId().equals(sale.holdId())) {

                            assertTrue(
                                    engine.confirm(other.holdId()).isErr(),
                                    "Another hold confirmed on a sold seat"
                            );
                        }
                    }

                    assertTrue(
                            engine.release(sale.holdId()).isErr(),
                            "Confirmed sale must not be releasable"
                    );

                    assertEquals(
                            new SeatStatus.Sold(sale.userId()),
                            engine.seatStatus(sale.seatId())
                    );
                }

            } catch (Exception | AssertionError failure) {
                throw new AssertionError(
                        "Stress failure: seed=" + seed
                                + ", episode=" + episodeId,
                        failure
                );
            }
        }
    }

    @Test
    @DisplayName("Concurrent same-user requests cannot exceed four holds")
    void concurrentSameUserCannotExceedFourHolds() throws Exception {
        for (int round = 0; round < 20; round++) {
            FakeClock clock = new FakeClock(Instant.EPOCH);
            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

            List<Callable<Result<String>>> actions = new ArrayList<>();

            for (int i = 0; i < 12; i++) {
                String seat = "seat-" + i;

                actions.add(() ->
                        engine.hold(seat, "alice", 60)
                );
            }

            List<Result<String>> results = runConcurrently(actions);

            assertEquals(
                    4L,
                    results.stream().filter(Result::isOk).count(),
                    "Exactly four requests should succeed"
            );

            long heldCount = 0;

            for (int i = 0; i < 12; i++) {
                SeatStatus status = engine.seatStatus("seat-" + i);

                if (status instanceof SeatStatus.Held held) {
                    assertEquals("alice", held.userId());
                    heldCount++;
                }
            }

            /*
             * Time never advances and there are no releases.
             * An oversubscription would remain visible here.
             */
            assertEquals(4L, heldCount);
        }
    }

    @Test
    @DisplayName("Opposite-order overlapping batches do not deadlock")
    void overlappingBatchesDoNotDeadlock() throws Exception {
        for (int round = 0; round < 20; round++) {
            FakeClock clock = new FakeClock(Instant.EPOCH);
            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

            List<Callable<Result<List<String>>>> actions = List.of(
                    () -> engine.holdMany(
                            "alice",
                            List.of("A", "B"),
                            60
                    ),
                    () -> engine.holdMany(
                            "bob",
                            List.of("B", "A"),
                            60
                    )
            );

            List<Result<List<String>>> results =
                    runConcurrently(actions);

            assertEquals(
                    1L,
                    results.stream().filter(Result::isOk).count(),
                    "Only one conflicting batch may succeed"
            );

            String winner = results.get(0).isOk()
                    ? "alice"
                    : "bob";

            SeatStatus.Held a = assertInstanceOf(
                    SeatStatus.Held.class,
                    engine.seatStatus("A")
            );

            SeatStatus.Held b = assertInstanceOf(
                    SeatStatus.Held.class,
                    engine.seatStatus("B")
            );

            assertEquals(winner, a.userId());
            assertEquals(winner, b.userId());
        }
    }

    @Test
    @DisplayName("Expired confirmation versus takeover has exactly one winner")
    void expiredConfirmVersusTakeover() throws Exception {
        for (int round = 0; round < 40; round++) {
            FakeClock clock = new FakeClock(Instant.EPOCH);
            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

            Result<String> initial = engine.hold("A", "alice", 1);
            assertTrue(initial.isOk());

            clock.advanceSeconds(2);

            List<Callable<Result<String>>> actions = List.of(
                    () -> engine.confirm(initial.getValue()),
                    () -> engine.hold("A", "bob", 60)
            );

            List<Result<String>> results =
                    runConcurrently(actions);

            boolean aliceConfirmed = results.get(0).isOk();
            boolean bobAcquired = results.get(1).isOk();

            assertNotEquals(
                    aliceConfirmed,
                    bobAcquired,
                    "Exactly one operation must succeed"
            );

            if (aliceConfirmed) {
                assertEquals(
                        new SeatStatus.Sold("alice"),
                        engine.seatStatus("A")
                );
            } else {
                SeatStatus.Held held = assertInstanceOf(
                        SeatStatus.Held.class,
                        engine.seatStatus("A")
                );

                assertEquals("bob", held.userId());
                assertTrue(engine.confirm(initial.getValue()).isErr());
                assertTrue(
                        engine.confirm(results.get(1).getValue()).isOk()
                );

                assertEquals(
                        new SeatStatus.Sold("bob"),
                        engine.seatStatus("A")
                );
            }
        }
    }

    @Test
    @DisplayName("Concurrent confirm and release have one terminal winner")
    void confirmVersusRelease() throws Exception {
        for (int round = 0; round < 30; round++) {
            FakeClock clock = new FakeClock(Instant.EPOCH);
            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

            Result<String> initial = engine.hold("A", "alice", 60);
            assertTrue(initial.isOk());

            List<Callable<Result<String>>> actions = List.of(
                    () -> engine.confirm(initial.getValue()),
                    () -> engine.release(initial.getValue())
            );

            List<Result<String>> results =
                    runConcurrently(actions);

            assertNotEquals(
                    results.get(0).isOk(),
                    results.get(1).isOk(),
                    "Confirm and release cannot both win"
            );

            if (results.get(0).isOk()) {
                assertEquals(
                        new SeatStatus.Sold("alice"),
                        engine.seatStatus("A")
                );
            } else {
                assertInstanceOf(
                        SeatStatus.Free.class,
                        engine.seatStatus("A")
                );

                assertTrue(
                        engine.confirm(initial.getValue()).isErr()
                );
            }
        }
    }

    @Test
    @DisplayName("Model: random operations match the independent reference")
    void modelBasedTest() {
        long seed = Long.getLong("model.seed", 20261016L);
        System.out.println("[ModelBasedTest] seed = " + seed);

        Random random = new Random(seed);
        List<String> seats = createSeats(SEAT_COUNT);
        List<String> users = List.of("alice", "bob", "charlie");

        for (int episode = 0; episode < 60; episode++) {
            FakeClock clock = new FakeClock(Instant.EPOCH);

            SeatHoldEngine engine = new SeatHoldEngineImpl(clock);
            ReferenceEngine reference = new ReferenceEngine(clock);

            List<HoldPair> pairs = new ArrayList<>();

            Map<String, String> engineToReference = new HashMap<>();
            Map<String, String> referenceToEngine = new HashMap<>();

            Deque<String> trace = new ArrayDeque<>();

            for (int step = 0; step < 160; step++) {
                String seat = seats.get(
                        random.nextInt(seats.size())
                );

                String user = users.get(
                        random.nextInt(users.size())
                );

                long ttl = 1 + random.nextInt(8);
                int operation = random.nextInt(7);

                String context = "seed=" + seed
                        + ", episode=" + episode
                        + ", step=" + step;

                try {
                    switch (operation) {
                        case 0, 1 -> {
                            trace.addLast(
                                    "hold(" + seat + "," + user + "," + ttl + ")"
                            );

                            Result<String> actual =
                                    engine.hold(seat, user, ttl);

                            Result<String> expected =
                                    reference.hold(seat, user, ttl);

                            assertEquals(
                                    expected.isOk(),
                                    actual.isOk(),
                                    context + ": hold outcome"
                            );

                            if (actual.isOk()) {
                                bindIds(
                                        actual.getValue(),
                                        expected.getValue(),
                                        engineToReference,
                                        referenceToEngine,
                                        pairs,
                                        context
                                );
                            }
                        }

                        case 2 -> {
                            List<String> batch = new ArrayList<>();
                            int size = random.nextInt(7);

                            for (int i = 0; i < size; i++) {
                                batch.add(
                                        seats.get(
                                                random.nextInt(seats.size())
                                        )
                                );
                            }

                            trace.addLast(
                                    "holdMany(" + user + "," + batch
                                            + "," + ttl + ")"
                            );

                            Result<List<String>> actual =
                                    engine.holdMany(user, batch, ttl);

                            Result<List<String>> expected =
                                    reference.holdMany(user, batch, ttl);

                            assertEquals(
                                    expected.isOk(),
                                    actual.isOk(),
                                    context + ": batch outcome"
                            );

                            if (actual.isOk()) {
                                assertEquals(
                                        expected.getValue().size(),
                                        actual.getValue().size(),
                                        context + ": batch result size"
                                );

                                for (int i = 0;
                                     i < actual.getValue().size();
                                     i++) {

                                    bindIds(
                                            actual.getValue().get(i),
                                            expected.getValue().get(i),
                                            engineToReference,
                                            referenceToEngine,
                                            pairs,
                                            context
                                    );
                                }
                            }
                        }

                        case 3, 4 -> {
                            HoldPair pair;

                            if (pairs.isEmpty()
                                    || random.nextInt(8) == 0) {

                                pair = new HoldPair(
                                        "unknown",
                                        "unknown"
                                );
                            } else {
                                pair = pairs.get(
                                        random.nextInt(pairs.size())
                                );
                            }

                            trace.addLast(
                                    (operation == 3
                                            ? "confirm("
                                            : "release(")
                                            + pair + ")"
                            );

                            Result<String> actual;
                            Result<String> expected;

                            if (operation == 3) {
                                actual = engine.confirm(
                                        pair.engineId()
                                );

                                expected = reference.confirm(
                                        pair.referenceId()
                                );
                            } else {
                                actual = engine.release(
                                        pair.engineId()
                                );

                                expected = reference.release(
                                        pair.referenceId()
                                );
                            }

                            assertEquals(
                                    expected.isOk(),
                                    actual.isOk(),
                                    context + ": confirm/release outcome"
                            );

                            if (actual.isOk()) {
                                assertEquals(
                                        expected.getValue(),
                                        actual.getValue(),
                                        context
                                );
                            }
                        }

                        case 5 -> {
                            int millis = random.nextInt(4001);
                            trace.addLast(
                                    "advanceMillis(" + millis + ")"
                            );
                            clock.advanceMillis(millis);
                        }

                        case 6 -> trace.addLast(
                                "seatStatus(" + seat + ")"
                        );

                        default -> throw new AssertionError(
                                "Unexpected operation"
                        );
                    }

                    /*
                     * Record equality checks state, user and expiry.
                     * Compare every seat after every operation.
                     */
                    for (String candidate : seats) {
                        assertEquals(
                                reference.seatStatus(candidate),
                                engine.seatStatus(candidate),
                                context + ": status of " + candidate
                        );
                    }

                    for (String candidateUser : users) {
                        long count = seats.stream()
                                .map(engine::seatStatus)
                                .filter(status ->
                                        status instanceof SeatStatus.Held held
                                                && held.userId().equals(
                                                        candidateUser
                                                )
                                )
                                .count();

                        assertTrue(
                                count <= 4,
                                context + ": " + candidateUser
                                        + " has " + count + " active holds"
                        );
                    }

                    while (trace.size() > 20) {
                        trace.removeFirst();
                    }

                } catch (AssertionError | RuntimeException failure) {
                    throw new AssertionError(
                            context + "\nRecent operations: " + trace,
                            failure
                    );
                }
            }
        }
    }

    private static void bindIds(
            String engineId,
            String referenceId,
            Map<String, String> engineToReference,
            Map<String, String> referenceToEngine,
            List<HoldPair> pairs,
            String context
    ) {
        assertNotNull(engineId, context);
        assertNotNull(referenceId, context);

        String previousReference = engineToReference.putIfAbsent(
                engineId,
                referenceId
        );

        String previousEngine = referenceToEngine.putIfAbsent(
                referenceId,
                engineId
        );

        if (previousReference != null) {
            assertEquals(
                    previousReference,
                    referenceId,
                    context + ": engine reused an ID incorrectly"
            );
        }

        if (previousEngine != null) {
            assertEquals(
                    previousEngine,
                    engineId,
                    context + ": idempotent retry changed the hold ID"
            );
        }

        if (previousReference == null) {
            pairs.add(new HoldPair(engineId, referenceId));
        }
    }

    private static void rememberHold(
            Map<String, TrackedHold> holds,
            TrackedHold hold
    ) {
        assertNotNull(hold.holdId());

        TrackedHold previous = holds.putIfAbsent(
                hold.holdId(),
                hold
        );

        if (previous != null) {
            assertEquals(
                    previous,
                    hold,
                    "A hold ID was reused for a different seat or user"
            );
        }
    }

    private static TrackedHold pickRandomHold(
            Map<String, TrackedHold> holds,
            Random random
    ) {
        List<TrackedHold> snapshot = new ArrayList<>(
                holds.values()
        );

        if (snapshot.isEmpty()) {
            return null;
        }

        return snapshot.get(
                random.nextInt(snapshot.size())
        );
    }

    private static List<String> createSeats(int count) {
        List<String> seats = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            seats.add("seat-" + i);
        }

        return seats;
    }

    /*
     * Starts tasks together and collects EVERY Future.
     * Worker exceptions therefore fail the calling test.
     */
    private static <T> List<T> runConcurrently(
            List<Callable<T>> actions
    ) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(
                actions.size(),
                task -> {
                    Thread thread = new Thread(task);

                    /*
                     * A broken intrinsic-lock implementation may ignore
                     * interruption. Daemon workers avoid hanging the JVM
                     * after the test's timeout has already failed.
                     */
                    thread.setDaemon(true);
                    return thread;
                }
        );

        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<T>> futures = new ArrayList<>();

            for (Callable<T> action : actions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();

                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "Timed out waiting for start signal"
                        );
                    }

                    return action.call();
                }));
            }

            assertTrue(
                    ready.await(5, TimeUnit.SECONDS),
                    "Workers did not become ready"
            );

            start.countDown();

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(20);

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                long remaining = deadline - System.nanoTime();

                assertTrue(
                        remaining > 0,
                        "Concurrent operations timed out; possible deadlock"
                );

                results.add(
                        future.get(
                                remaining,
                                TimeUnit.NANOSECONDS
                        )
                );
            }

            return results;

        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}