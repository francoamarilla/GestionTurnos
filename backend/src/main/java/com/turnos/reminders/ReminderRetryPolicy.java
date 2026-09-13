package com.turnos.reminders;

import java.time.Duration;

// ReminderSent only has SENT/FAILED (spec section 3.1 - no separate
// "pending retry" status), so a FAILED row below MAX_ATTEMPTS just means
// "still retrying", not "given up" - only once attempts reaches
// MAX_ATTEMPTS is it the terminal, dashboard-visible failure the spec
// describes ("agotados, se marca FAILED"). Shared by ReminderTenantSweeper
// (decides whether/when to retry) and ReminderSentRepository's failed-
// reminders query (decides what counts as "exhausted" for the dashboard
// alert), so the two can't drift apart.
final class ReminderRetryPolicy {

    static final int MAX_ATTEMPTS = 5;

    // Doubling schedule anchored to the first attempt's timestamp (no
    // separate "last attempt at" column in the spec's ReminderSent shape -
    // don't invent one): 15, 30, 60, then capped at 120 minutes between
    // retries.
    static Duration backoffDelay(int attemptsSoFar) {
        long minutes = 15L << Math.min(attemptsSoFar - 1, 3);
        return Duration.ofMinutes(minutes);
    }

    private ReminderRetryPolicy() {
    }
}
