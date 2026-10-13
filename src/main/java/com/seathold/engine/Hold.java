package com.seathold.engine;

import java.time.Instant;

/**
 * Represents a seat hold: a temporary reservation that a user
 * must confirm (pay) before the TTL runs out.
 */
public class Hold {

    public enum State { ACTIVE, CONFIRMED, RELEASED }

    private final String holdId;
    private final String seatId;
    private final String userId;
    private final Instant expiresAt;
    private volatile State state;

    public Hold(String holdId, String seatId, String userId, Instant expiresAt) {
        this.holdId   = holdId;
        this.seatId   = seatId;
        this.userId   = userId;
        this.expiresAt = expiresAt;
        this.state    = State.ACTIVE;
    }

    public String getHoldId()   { return holdId;   }
    public String getSeatId()   { return seatId;   }
    public String getUserId()   { return userId;   }
    public Instant getExpiresAt(){ return expiresAt; }
    public State getState()     { return state;    }

    public void setState(State s){ this.state = s; }

    /** True when the wall clock is past the TTL boundary. */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    @Override
    public String toString() {
        return "Hold{holdId=" + holdId + ", seatId=" + seatId
                + ", userId=" + userId + ", state=" + state
                + ", expiresAt=" + expiresAt + "}";
    }
}
