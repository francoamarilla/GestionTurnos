package com.turnos.appointments;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

// professionalId/serviceId default to the original appointment's values if
// omitted - "mismo servicio/profesional por defecto, con opción de cambiar"
// (spec section 5). The customer always carries over; reassigning to a
// different customer isn't part of "reprogramar".
public record RescheduleAppointmentRequest(
        @NotNull OffsetDateTime startTime, UUID professionalId, UUID serviceId) {
}
