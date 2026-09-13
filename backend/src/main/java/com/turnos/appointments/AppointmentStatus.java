package com.turnos.appointments;

// Terminal states (COMPLETED, CANCELLED, NO_SHOW) never transition further.
// "Rescheduled" is not a state here - it's cancel-current + create-new,
// linked via rescheduledFromAppointmentId (spec section 3.3).
public enum AppointmentStatus {
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
