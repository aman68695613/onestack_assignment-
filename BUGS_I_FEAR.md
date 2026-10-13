# BUGS_I_FEAR.md

## One bug class my tests would NOT catch — and how I would catch it in production

### The bug: ABA-problem in lazy expiry confirmation

#### What it is

Consider this sequence:

1. Alice holds seat A1 with TTL=5s. Her hold object is `H1`.
2. Alice's hold expires. She does NOT confirm immediately.
3. Bob races in and calls `hold("A1", "bob", ...)`. The engine detects H1 is
   expired, calls `expireHold(H1)`, decrements Alice's count, and puts a new
   hold `H2` in `activeHolds["A1"]`.
4. Bob then immediately releases H2. `activeHolds["A1"]` is now null.
5. Charlie calls `hold("A1", "charlie", ...)` and gets H3.
6. Now Alice (whose client was slow) finally calls `confirm(H1)`.

In my implementation `doConfirm` checks:
```java
Hold current = activeHolds.get(h.getSeatId());
if (current != h) { return Err(...) }
```

Because `current` is H3 (not H1), Alice's confirm fails correctly. ✓

But here is the subtle risk: **what if the JVM or OS reuses the same heap address
for H3 that it used for H1?** In Java, object identity (`==` on references, not
`equals`) cannot be fooled by heap address reuse unless we have a bug where we
cache raw addresses — which we do not in Java. So this specific scenario is
actually safe in Java.

#### The REAL bug class I fear

The actual dangerous scenario is a **linearizability violation in the user-count
accounting** that my randomised stress test might not catch:

1. Two threads both read `userHoldCount.getOrDefault("alice", 0)` as 3.
2. Both conclude Alice is under the limit.
3. Both proceed to create a hold, and Alice ends up with 5 holds.

My current implementation uses `ConcurrentHashMap.compute()` / `merge()` for
atomic increments of the count, and I hold the per-seat lock when I check-then-
increment. BUT: the check (`count >= 4`) and the increment happen inside the
same `synchronized(seatLock)` block, and seatLocks are per-seat. Alice could
be simultaneously holding two different seat locks from two threads, each
reading a pre-increment count of 3.

The stress test does not reliably catch this because with 4 users and 5 seats it
would need the exact race where the same user concurrently acquires different seats
and both read the same stale count.

#### How I would catch it in production

1. **Structured invariant logging:** After every `hold()` call, log a metric
   `user.hold.count.<userId>` to a time-series store. An alert fires if
   `max(count) > 4` per user.
2. **Post-hoc audit job:** A periodic job queries the hold log and counts
   per-user active holds at each millisecond timestamp. Any window where count > 4
   is flagged.
3. **Better fix in code:** Use a single per-user `ReentrantLock` (in addition to
   per-seat locks) when modifying the user count, or use a `StampedLock` / atomic
   compare-and-swap on the count before creating the hold. This eliminates the
   race entirely rather than relying on the stress test to surface it.
