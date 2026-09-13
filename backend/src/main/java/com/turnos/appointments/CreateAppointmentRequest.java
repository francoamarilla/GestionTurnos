package com.turnos.appointments;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

// customerId is nullable: an anonymous walk-in is a valid appointment
// (spec section 3.1). startTime must exactly match one of the slots
// GET /api/availability would offer for this professional/service/date -
// the dashboard (and bot) always picks from that list, never types a time.
public record CreateAppointmentRequest(
        @NotNull UUID professionalId, @NotNull UUID serviceId, @NotNull OffsetDateTime startTime, UUID customerId) {
}
