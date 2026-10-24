package com.ossature.interviewer.security;

import java.time.Instant;

/** Remembers which WebSocket tickets have been used, so each can be redeemed only once. */
public interface TicketRedemptions {

    /** True the first time this ticket id is presented, false for every later attempt (until it expires anyway). */
    boolean firstUse(String ticketId, Instant expiresAt);
}
