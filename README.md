# Seat Hold Engine

A concurrency-safe, in-memory seat-hold engine written in Java 17 with Maven and JUnit 5.

## What it does

Solves the "nobody gets the same seat twice" problem for a concert-ticketing system.
Users can temporarily *hold* a seat while they complete payment. If they don't confirm
in time, the seat is released for others. The engine is safe under high concurrency.

## How to run

**Prerequisites:** Java 17+ and Maven 3.8+

```bash
# Run all automated tests (unit + stress + model-based)
mvn test

# Run manual interactive CLI test console
mvn compile exec:java

# Or run the automated walkthrough demo
java -cp target/classes com.seathold.engine.DemoRunner --demo
```

Expected output: `BUILD SUCCESS` with all tests passing.

## Project layout

```
seat-hold-engine/
├── pom.xml
├── DECISIONS.md      ← rule-conflict resolutions (read this first)
├── AI_USAGE.md
├── BUGS_I_FEAR.md
└── src/
    ├── main/java/com/seathold/engine/
    │   ├── SeatHoldEngine.java        ← public interface
    │   ├── SeatHoldEngineImpl.java    ← thread-safe implementation
    │   ├── Hold.java                  ← hold domain object
    │   ├── SeatStatus.java            ← sealed status type (Free/Held/Sold)
    │   ├── Result.java                ← typed Ok/Err return value
    │   ├── FakeClock.java             ← injectable clock for tests
    │   └── DemoRunner.java            ← interactive manual test console
    └── test/java/com/seathold/engine/
        ├── SeatHoldEngineTest.java    ← unit tests (all 6 rules + edge cases)
        ├── ConcurrencyStressTest.java ← multi-threaded invariant + model test
        └── ReferenceEngine.java       ← single-threaded reference for model test
```

## API

```java
SeatHoldEngine engine = new SeatHoldEngineImpl(); // real clock

Result<String>       r = engine.hold("A1", "alice", 60);      // ttl=60s
Result<String>       r = engine.confirm(holdId);
Result<String>       r = engine.release(holdId);
SeatStatus           s = engine.seatStatus("A1");              // Free | Held | Sold
Result<List<String>> r = engine.holdMany("alice", List.of("A1","B2"), 60);
```

## What works

- All 6 business rules (R1–R6)
- Idempotent hold (R5) and all-or-nothing holdMany (R6)
- Expired-hold confirmation (R3) — seat is only blocked if someone else took it
- Per-seat locking (no global lock, scales under concurrency)
- Deadlock-free holdMany via sorted lock acquisition
- Injectable clock — zero Thread.sleep in tests
- Concurrency stress test (16 threads, random operations, invariant checks)
- Model-based test comparing engine against reference implementation

## What doesn't work / known limitations

- No persistence — all state is lost on restart
- Lazy expiry: if no one touches an expired seat, the per-user hold count remains
  inflated until someone requests that seat. This can briefly prevent a user from
  holding a new seat even though their old hold expired.
- `holdMany` with >4 items immediately fails the per-user check even if the user
  holds 0 seats (they can request at most 4).
