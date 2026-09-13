package com.turnos.reminders;

import java.util.UUID;

// Backs the dashboard's "recordatorios que fallaron al enviarse" alert
// (spec section 7, Inicio screen).
public record FailedReminderResponse(UUID appointmentId, ReminderType type, int attempts) {

    static FailedReminderResponse from(ReminderSent reminderSent) {
        return new FailedReminderResponse(
                reminderSent.getAppointmentId(), reminderSent.getType(), reminderSent.getAttempts());
    }
}
