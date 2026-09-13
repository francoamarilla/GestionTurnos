package com.turnos.appointments;

import jakarta.validation.constraints.NotNull;

// CONFIRMED is never a valid target here - it's the state a new appointment
// starts in, not something transitioned to (spec section 3.3).
public record UpdateAppointmentStatusRequest(@NotNull AppointmentStatus status) {
}
