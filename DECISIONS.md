# DECISIONS.md — Rule Conflicts and Resolutions

## The Catches in the Spec

The assignment explicitly asks you to look at R3, R4, R5 together and R2 with R6.
Here is how I read each conflict and what I decided.

---

### R3 vs R4 — Expired-but-confirmable vs. Holds Are Never Extended

**The conflict:**
R3 says an expired hold can still be confirmed if nobody else grabbed the seat.
R4 says holds are never extended.

On the surface these sound contradictory: if you can confirm an expired hold,
aren't you effectively extending it?

**My reading:**
They describe different things. R4 is about the *TTL end-time* — you cannot call
hold() a second time and get a later expiry. R3 is about the *confirmation window*
after the clock has already passed the expiry. The seat is logically free from the
outside world's perspective (seatStatus returns FREE), but the original holder can
still "sneak in" a confirmation as long as nobody else physically claimed the seat.

**Decision:**
- `hold()` for the same user+seat returns the same holdId with the original expiry (R4 ∩ R5).
- `confirm(holdId)` succeeds whenever the engine's internal `activeHolds[seatId]`
  map still points to that exact Hold object. This is true even after TTL. The moment
  another user calls `hold()` and wins the seat, the pointer changes and the old
  holdId's confirm returns an error.

This is the UX intent: "customers hate losing a seat because of a slow payment page."

---

### R4 vs R5 — No TTL Extension vs. Idempotent Retry

**The conflict:**
R5 says treat a repeat hold by the same user on the same seat as an idempotent retry.
R4 says holds are never extended.

If a client retries with a different (larger) `ttlSeconds`, which wins?

**Decision:**
R4 beats R5 on TTL. We return the same holdId AND keep the original expiry.
We completely ignore the `ttlSeconds` argument on an idempotent retry.

Rationale: "idempotent retry" means the second call looks exactly like the first
to the caller. If we accepted the new TTL we'd be silently granting an extension
in disguise, which contradicts R4 and creates a security surface (clients spamming
hold() to keep a seat forever).

---

### R2 vs R6 — Per-User Limit vs. All-or-Nothing Batch

**The conflict:**
R2 caps a user at 4 concurrent holds.
R6 says holdMany is all-or-nothing.

If a user with 3 holds requests 2 more via holdMany, we must refuse the whole batch
(not partially grant 1 and refuse the other). But the per-seat loop in a naive
implementation might hold the first seat, then fail on the second, leaving the
user in an inconsistent state.

**Decision:**
Count the *net new seats* the batch would add before acquiring any individual
seat lock. If `currentCount + netNew > 4` we return an error immediately without
touching a single seat. This means:

1. The check is atomic with respect to the batch (all locks are held when we run it).
2. Seats that the same user already holds inside the batch are counted *once*
   (idempotency: they don't increment the counter again).

If the pre-check passes but a seat turns out to be unavailable mid-batch we roll
back all seats we already claimed in that batch.

---

### R2 + R3 — User Count Accounting for Expired Holds

An expired hold is logically free, but the engine performs *lazy* expiry (it only
removes the hold when someone tries to use that seat). This means the per-user
count could be stale: a user might appear to hold 4 seats when 2 of them are
expired.

**Decision:**
We decrement the user count eagerly *at the time we discover the expiry* (inside
`doHold` when we detect an expired hold on the seat being requested). This keeps
the count accurate without a background sweeper thread.

The downside: if no one ever touches an expired seat, the count is stale until
someone does. In practice this means a user could be blocked from holding a 5th
seat even though some of their holds expired — but only briefly. This is a
deliberate trade-off for simplicity over a background GC.

---

### Deadlock prevention in holdMany

When two threads call holdMany with overlapping seat sets in opposite orders:

- Thread A: holdMany(user, ["seat-2", "seat-1"], ...)
- Thread B: holdMany(user, ["seat-1", "seat-2"], ...)

If we acquire locks in list order, A locks seat-2 and waits for seat-1 while B
locks seat-1 and waits for seat-2 → classic deadlock.

**Decision:** Sort seat IDs lexicographically before acquiring locks. Both threads
will then try to acquire seat-1 first, so one of them waits and there is no cycle.
This is standard lock-ordering and is explained in the code.
