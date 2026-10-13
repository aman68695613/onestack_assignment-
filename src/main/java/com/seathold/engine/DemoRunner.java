package com.seathold.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive CLI runner allowing developers to manually test and inspect
 * all SeatHoldEngine APIs in real-time.
 *
 * Can be run via:
 *   java -cp target/classes com.seathold.engine.DemoRunner
 */
public class DemoRunner {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public static void main(String[] args) {
        FakeClock clock = new FakeClock(Instant.now());
        SeatHoldEngine engine = new SeatHoldEngineImpl(clock);

        if (args.length > 0 && args[0].equalsIgnoreCase("--demo")) {
            runAutomatedWalkthrough(engine, clock);
            return;
        }

        System.out.println("=================================================");
        System.out.println("       SEAT HOLD ENGINE - MANUAL TEST CONSOLE    ");
        System.out.println("=================================================");
        System.out.println("Available Commands:");
        System.out.println("  hold <seat> <user> [ttlSeconds]     -> Hold a seat (default ttl: 30s)");
        System.out.println("  confirm <holdId>                    -> Confirm and purchase held seat");
        System.out.println("  release <holdId>                    -> Release a hold");
        System.out.println("  status <seat>                       -> Check seat status (FREE/HELD/SOLD)");
        System.out.println("  holdMany <user> <s1,s2,...> [ttl]   -> All-or-nothing batch hold");
        System.out.println("  advance <seconds>                   -> Move the clock forward");
        System.out.println("  time                                -> Show current clock time");
        System.out.println("  demo                                -> Run automated walkthrough");
        System.out.println("  exit                                -> Exit console");
        System.out.println("=================================================\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("[" + TIME_FMT.format(clock.get()) + "] > ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "exit", "quit" -> {
                        System.out.println("Bye!");
                        return;
                    }
                    case "time" -> {
                        System.out.println("Current clock time: " + clock.get() + " (" + TIME_FMT.format(clock.get()) + ")");
                    }
                    case "advance" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: advance <seconds>");
                            break;
                        }
                        long sec = Long.parseLong(parts[1]);
                        clock.advanceSeconds(sec);
                        System.out.println("Clock advanced by " + sec + "s. Current time: " + TIME_FMT.format(clock.get()));
                    }
                    case "hold" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: hold <seat> <user> [ttlSeconds]");
                            break;
                        }
                        String seat = parts[1];
                        String user = parts[2];
                        long ttl = parts.length >= 4 ? Long.parseLong(parts[3]) : 30;
                        Result<String> r = engine.hold(seat, user, ttl);
                        if (r.isOk()) {
                            System.out.println(" SUCCESS: Hold granted! holdId = " + r.getValue());
                        } else {
                            System.out.println(" ERROR: " + r.getError());
                        }
                    }
                    case "confirm" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: confirm <holdId>");
                            break;
                        }
                        Result<String> r = engine.confirm(parts[1]);
                        if (r.isOk()) {
                            System.out.println(" SUCCESS: Seat confirmed and marked SOLD!");
                        } else {
                            System.out.println(" ERROR: " + r.getError());
                        }
                    }
                    case "release" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: release <holdId>");
                            break;
                        }
                        Result<String> r = engine.release(parts[1]);
                        if (r.isOk()) {
                            System.out.println(" SUCCESS: Hold released. Seat is now FREE.");
                        } else {
                            System.out.println(" ERROR: " + r.getError());
                        }
                    }
                    case "status" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: status <seat>");
                            break;
                        }
                        SeatStatus status = engine.seatStatus(parts[1]);
                        if (status instanceof SeatStatus.Free) {
                            System.out.println(" Seat " + parts[1] + " is FREE");
                        } else if (status instanceof SeatStatus.Held h) {
                            System.out.println(" Seat " + parts[1] + " is HELD by " + h.userId()
                                    + " (expires at " + TIME_FMT.format(h.expiresAt()) + ")");
                        } else if (status instanceof SeatStatus.Sold s) {
                            System.out.println(" Seat " + parts[1] + " is SOLD to " + s.userId());
                        }
                    }
                    case "holdmany" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: holdMany <user> <seat1,seat2,...> [ttlSeconds]");
                            break;
                        }
                        String user = parts[1];
                        List<String> seats = Arrays.asList(parts[2].split(","));
                        long ttl = parts.length >= 4 ? Long.parseLong(parts[3]) : 30;
                        Result<List<String>> r = engine.holdMany(user, seats, ttl);
                        if (r.isOk()) {
                            System.out.println(" SUCCESS: Batch held! holdIds = " + r.getValue());
                        } else {
                            System.out.println(" ERROR: " + r.getError());
                        }
                    }
                    case "demo" -> {
                        runAutomatedWalkthrough(engine, clock);
                    }
                    default -> {
                        System.out.println("Unknown command: '" + cmd + "'. Type 'demo', 'hold', 'status', 'confirm', 'release', 'advance', or 'exit'.");
                    }
                }
            } catch (Exception e) {
                System.out.println("Execution error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    private static void runAutomatedWalkthrough(SeatHoldEngine engine, FakeClock clock) {
        System.out.println("\n--- RUNNING AUTOMATED SCENARIO WALKTHROUGH ---\n");

        System.out.println("1. Alice holds seat A1 for 30s...");
        Result<String> h1 = engine.hold("A1", "alice", 30);
        System.out.println("   Result: " + (h1.isOk() ? "holdId=" + h1.getValue() : "error=" + h1.getError()));
        System.out.println("   Status A1: " + engine.seatStatus("A1"));

        System.out.println("\n2. Bob tries to hold seat A1 concurrently...");
        Result<String> hBob = engine.hold("A1", "bob", 30);
        System.out.println("   Result (expected failure): " + (hBob.isOk() ? "ok" : hBob.getError()));

        System.out.println("\n3. Alice idempotent retry on seat A1 with ttl=100s...");
        Result<String> hRetry = engine.hold("A1", "alice", 100);
        System.out.println("   Result: holdId=" + hRetry.getValue() + " (matches previous: " + hRetry.getValue().equals(h1.getValue()) + ")");

        System.out.println("\n4. Advancing clock past TTL (+40s)...");
        clock.advanceSeconds(40);
        System.out.println("   Status A1: " + engine.seatStatus("A1") + " (expired -> appears FREE)");

        System.out.println("\n5. Rule 3: Alice confirms her expired hold before anyone else claims it...");
        Result<String> conf = engine.confirm(h1.getValue());
        System.out.println("   Result: " + (conf.isOk() ? "Confirmed!" : conf.getError()));
        System.out.println("   Status A1: " + engine.seatStatus("A1") + " (now SOLD)");

        System.out.println("\n6. Charlie attempts holdMany on ['B1', 'B2', 'B3']...");
        Result<List<String>> batch = engine.holdMany("charlie", List.of("B1", "B2", "B3"), 30);
        System.out.println("   Result: " + (batch.isOk() ? "Held seats: " + batch.getValue() : batch.getError()));

        System.out.println("\n7. Charlie tries holding 2 more seats (total would exceed limit 4)...");
        Result<List<String>> overLimit = engine.holdMany("charlie", List.of("C1", "C2"), 30);
        System.out.println("   Result (expected rollback): " + (overLimit.isOk() ? "ok" : overLimit.getError()));
        System.out.println("   Status C1: " + engine.seatStatus("C1") + " (unaffected)");

        System.out.println("\n--- WALKTHROUGH COMPLETE ---\n");
    }
}
