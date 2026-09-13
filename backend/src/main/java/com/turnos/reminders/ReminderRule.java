package com.turnos.reminders;

// "Una regla de recordatorio con un offset configurable respecto a la hora
// del turno" (spec section 6) - modeled as data (offsetHours is a field,
// not a hardcoded branch per reminder type), not as a per-tenant setting:
// spec section 7's dashboard screens don't list a reminder-rules management
// UI, so none is added for the MVP. The two default rules live as a plain
// list in ReminderSweepJob.
public record ReminderRule(ReminderType type, int offsetHours) {
}
