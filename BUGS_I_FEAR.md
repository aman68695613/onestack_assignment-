# BUGS_I_FEAR.md

## One bug class my tests would NOT catch — and how I would catch it in production

### The bug: Unbounded growth of in-memory bookkeeping

#### What it is

The engine keeps historical and locking information in several in-memory maps:

- `holdsById` retains every hold that has ever been created.
- `holdsByUser` retains references to a user's historical holds.
- `seatLocks` retains a lock object for every seat ID encountered.
- `userLocks` retains a lock object for every user ID encountered.
- `soldSeats` permanently retains confirmed seat purchases.

Some of this retention is intentional for the assignment because retaining
terminal hold information supports idempotent `confirm()` and `release()` calls.
Other retained structures, particularly lock objects and per-user history, are
a simplification rather than a business requirement.

The engine also has no persistence or external database.

However, in a long-running production process, the number of unique holds,
users and seat IDs could continue increasing. Even if every individual
operation is correct, these collections could eventually consume excessive
heap memory and cause increased garbage-collection pressure or an
`OutOfMemoryError`.

#### Why my tests might not catch it

The automated tests create only a limited number of users, seats and holds, and
the JVM terminates after the test suite completes.

They verify business rules, batch atomicity and concurrent behavior, but they do
not run for days or generate millions of unique IDs. A retention problem could
therefore remain invisible while all functional tests continue to pass.

This is different from a normal temporary allocation. The maps hold strong
references, so the garbage collector cannot reclaim those entries while the
engine remains alive.

#### How I would catch it in production

1. **Collection-size metrics:** Publish the sizes of `holdsById`,
   `holdsByUser`, `seatLocks`, `userLocks`, `activeHolds` and `soldSeats`.
   Alert when historical collections grow continuously without matching
   expected business volume.

2. **Heap and garbage-collection monitoring:** Track heap usage, old-generation
   occupancy, allocation rate, pause duration and full-GC frequency. A growing
   retained heap after traffic becomes stable would indicate a possible
   retention problem.

3. **Long-running load test:** Generate large numbers of unique users, seats
   and completed holds over several hours. After operations finish, allow a
   normal idle period and verify that retained memory reaches an expected
   plateau.

4. **Heap-dump analysis:** If memory continues growing, capture a heap dump and
   inspect the dominant retained objects. This would show whether the engine's
   maps and historical `Hold` objects are responsible.

#### How I would fix it

I would first define an explicit retention policy because deleting history can
change the semantics of repeated `confirm()` and `release()` requests.

Possible production solutions include:

- Persist terminal hold records in a database and remove them from memory after
  an idempotency-retention window.
- Replace old terminal holds with smaller tombstone records containing only the
  fields required for idempotent responses.
- Remove per-user history after all of that user's holds are terminal and the
  retention window has elapsed.
- Remove lock objects only when no operation can still reference them, or use a
  bounded or sharded locking strategy instead of indefinitely storing one lock
  per arbitrary identifier.
- Store the permanent sold-seat state in durable storage rather than depending
  on an ever-growing in-memory map.

Cleanup would require careful concurrency control. Removing a lock object while
another thread is using it could allow two different lock objects to exist for
the same seat or user, breaking mutual exclusion.

Therefore, I would not add a simple operation such as:

```java
seatLocks.remove(seatId);
```

without using reference counting, lock striping or another safe lock-lifecycle
design.