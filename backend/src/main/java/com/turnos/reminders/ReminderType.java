package com.turnos.reminders;

// spec section 3.1. CONFIRMATION and CANCELLATION are recorded here too
// (same idempotency table) even though this section's sweep job only ever
// produces REMINDER_24H/REMINDER_2H rows - CONFIRMATION goes out inline at
// booking time and CANCELLATION inline at cancellation time, both as
// business-initiated notifications outside this job (spec section 6).
public enum ReminderType {
    CONFIRMATION,
    REMINDER_24H,
    REMINDER_2H,
    CANCELLATION
}
