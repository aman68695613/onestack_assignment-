
# DECISIONS.md — Rule Conflicts and Resolutions

## The Catches in the Spec

The assignment explicitly asks us to consider R3, R4 and R5 together, and R2
together with R6.

This document explains how I interpreted each conflict and how the implementation
resolves it.

---

### R3 vs R4 — Expired-but-confirmable vs. Holds Are Never Extended

#### The conflict

R3 says an expired hold can still be confirmed if nobody else has acquired the
seat.

R4 says holds are never extended.

On the surface, these rules may appear contradictory: if an expired hold can
still be confirmed, does that effectively extend it?

#### My reading

The two rules describe different concepts.

R4 controls the hold's TTL end time. Calling `hold()` again must not move the
original expiry into the future.

R3 controls whether an expired hold may still be confirmed. After expiry, the
seat appears free through `seatStatus()`, and the hold no longer consumes the
user's quota. However, the original holder retains a conditional right to
confirm while the seat has not been explicitly released or successfully
acquired by somebody else.

Expiry therefore does not automatically mean release.

#### Decision

- An active idempotent `hold()` request for the same user and seat returns the
  original hold ID.
- The retry does not modify the original expiry.
- `seatStatus()` reports an expired hold as free.
- An expired hold does not consume one of the user's four quota slots.
- `confirm(holdId)` succeeds after expiry if the seat still belongs to that
  exact hold internally.
- A successful replacement invalidates the previous expired hold.
- A rejected replacement does not invalidate the previous expired hold.
- An explicitly released hold cannot later be confirmed.

The engine verifies late-confirmation ownership using the hold associated with
the seat. If the seat no longer points to the original hold, confirmation fails.

This supports the intended user experience: a slow payment page does not
automatically destroy the customer's chance to complete the purchase, but the
expired hold does not prevent another valid customer from acquiring the seat.

---

### R4 vs R5 — No TTL Extension vs. Idempotent Retry

#### The conflict

R5 says that a repeated hold request from the same user for the same seat should
be treated as an idempotent retry.

R4 says that holds are never extended.

The ambiguity appears when the retry supplies a different `ttlSeconds` value.

#### Decision

R4 controls the expiry behavior.

For an active hold owned by the same user, the engine:

- Returns the same hold ID.
- Preserves the original expiry.
- Ignores the retry's `ttlSeconds` value.

For example, if Alice holds A1 for 30 seconds and retries after 20 seconds with a
TTL of 100 seconds, the hold still expires at the original 30-second boundary.

Idempotency means the retry observes the result of the original operation. It
does not create a new hold or mutate the original hold.

Allowing the retry to replace the TTL would create an extension mechanism and
would let clients keep seats indefinitely by repeatedly calling `hold()`.

Once the original hold has expired, a new `hold()` request is treated as a new
acquisition rather than an idempotent retry.

---

### R2 vs R6 — Per-User Limit vs. Atomic Batch

#### The conflict

R2 limits a user to four active, non-expired holds.

R6 requires `holdMany()` to be all-or-nothing.

If a user with three active holds requests two new seats, the engine must reject
the entire batch. It must not grant one seat and reject the other.

A naive implementation might modify seats while iterating and then attempt to
undo those changes after discovering a conflict. That approach is dangerous
because rollback could accidentally release a hold that existed before the
batch or destroy an expired holder's late-confirmation right.

#### Decision

`holdMany()` uses a validate-before-commit strategy:

1. Remove duplicate seat IDs while preserving their first-occurrence order.
2. Sort the unique seat IDs for lock acquisition.
3. Acquire the requesting user's lock.
4. Acquire all relevant seat locks in sorted order.
5. Read one clock value for the operation.
6. Classify every requested seat.
7. Calculate the number of net-new active holds.
8. Verify the four-seat quota.
9. Verify that every requested seat can be acquired.
10. Commit all new holds only after every validation succeeds.

Seats already actively held by the same user are idempotent entries. They return
their existing hold IDs and do not consume another quota slot.

Duplicate seat IDs are processed once.

If validation fails, the method returns an error without creating, releasing,
replacing or confirming any hold.

Because no partial mutation occurs before validation succeeds, ordinary
validation failures require no rollback.

The user lock remains held across the quota check and commit. Therefore, another
operation for the same user cannot change that user's hold set between those
steps.

---

### R2 + R3 — Expired Holds and Quota

#### The conflict

An expired hold is logically free for new acquisition, but R3 says it may still
be confirmed if nobody else has acquired the seat.

This creates two separate questions:

1. Should the expired hold consume one of the user's four quota slots?
2. Should removing it from quota also invalidate its late-confirmation right?

Treating these as the same operation would create incorrect behavior. Marking an
expired hold as released merely to reduce the count would violate R3.

#### Decision

Expiry and invalidation are separate concepts.

An expired hold:

- Appears free through `seatStatus()`.
- Does not consume a quota slot.
- Remains eligible for late confirmation while it is still associated with the
  seat.
- Is not marked released merely because its TTL elapsed.

The engine calculates quota from active, non-expired holds rather than treating
every historical hold as quota-consuming.

When another user requests the expired seat, the engine first verifies that the
new request is valid, including the requesting user's quota.

Only after validation succeeds does the engine commit the replacement and
invalidate the expired hold.

Therefore:

- A successful takeover invalidates the old hold.
- A rejected takeover leaves the old hold confirmable.
- A failed batch leaves all previous expired-hold confirmation rights unchanged.
- No background expiry thread is required for quota correctness.

This preserves both R2 and R3: expired holds do not block a user from acquiring
new seats, but expiry alone does not destroy late-confirmation eligibility.

---

### Per-User Quota Serialization

The quota check and hold creation must be one atomic operation for a particular
user.

Using only per-seat locks is insufficient. Two threads could request different
seats for Alice, both observe that Alice currently has three holds and both
create another hold.

#### Decision

Every operation that can modify a user's active hold set first acquires that
user's lock:

```text
user lock -> seat lock or sorted seat locks
```

For example:

1. Alice has three active holds.
2. Thread A and Thread B request different seats for Alice.
3. Thread A acquires Alice's user lock.
4. Thread B waits for the same user lock.
5. Thread A creates Alice's fourth hold and releases the lock.
6. Thread B acquires the lock, observes four active holds and is rejected.

The per-user lock protects the complete check-and-commit operation. Atomic map
operations such as `compute()` or `merge()` alone would not make a separate
quota check and increment atomic.

---

### Deadlock Prevention in `holdMany`

Two batch requests may contain overlapping seats in different input orders:

```text
Thread A: holdMany("alice", ["seat-2", "seat-1"], ...)
Thread B: holdMany("bob",   ["seat-1", "seat-2"], ...)
```

If locks were acquired in input order:

- Thread A could lock `seat-2` and wait for `seat-1`.
- Thread B could lock `seat-1` and wait for `seat-2`.
- Neither thread could proceed.

Requests for the same user are serialized by the per-user lock, but requests
from different users can still overlap on seat locks.

#### Decision

The engine sorts unique seat IDs lexicographically before acquiring their locks.

Both operations therefore acquire locks in the same order:

```text
seat-1 -> seat-2
```

One operation may wait, but the wait cannot form a circular seat-lock
dependency.

The global lock order is:

```text
user lock -> sorted seat locks
```

All engine operations follow this order whenever both lock types are required.

The sorted list is used only for lock acquisition. Results are returned in the
order of each seat's first occurrence in the original input.

---

### Status Consistency

A seat transition may update more than one internal structure. For example,
confirmation removes the current hold and records the seat as sold.

Without locking, `seatStatus()` could observe an intermediate state and briefly
report the seat as free.

#### Decision

`seatStatus()` evaluates a seat's complete state while holding that seat's lock.

This ensures that each result corresponds to a consistent point in the
operation order:

- `FREE`
- `HELD`
- `SOLD`

The method cannot observe a partially committed confirmation, release or
replacement.

---

### Summary of Lock Ordering

The implementation follows these rules:

```text
Single-seat mutation:
user lock -> seat lock

Batch mutation:
user lock -> unique seat locks in sorted order

Status lookup:
seat lock
```

A method must not acquire a user lock while already holding a seat lock.

Keeping one consistent direction prevents lock-order cycles while allowing
operations involving unrelated users and seats to proceed concurrently.