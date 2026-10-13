# AI_USAGE.md

## Honest log of AI tool use

AI assistance (Antigravity / Claude) was used in this assignment.

### What was generated / assisted

- **Initial project scaffold:** Maven directory structure and pom.xml.
- **Implementation guidance:** Discussed the locking strategy (per-seat locks vs.
  global lock) and the sorted-lock-acquisition pattern for deadlock prevention in holdMany.
- **Test structure:** The overall test class layout and the idea of using a
  model-based test against a reference implementation.

### What was written / decided by the developer

- **All rule-conflict resolutions** (see DECISIONS.md) — these required careful
  reading of the spec and deliberate choices about trade-offs.
- **The actual business logic** in SeatHoldEngineImpl — particularly the R3
  "expired but confirmable" check using object identity (`bySeatId.get(id) == this`)
  and the user-count accounting during lazy expiry.
- **BUGS_I_FEAR.md** — identifying the ABA-problem-like risk.
- **All naming, structure, and code style** decisions.

### Verification

Every generated snippet was read, understood, and tested before inclusion.
The developer can explain every line and the rationale behind each decision.
