package com.turnos.appointments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

// CONFIRMED is never a valid target here - it's the state a new appointment
// starts in, not something transitioned to (spec section 3.3).
// paymentMethod and price are optional and only mean something with
// COMPLETED ("Cobrar este" in Ventas); Agenda and Inicio complete without
// them. A null price keeps the one frozen at booking time.
public record UpdateAppointmentStatusRequest(
        @NotNull AppointmentStatus status, PaymentMethod paymentMethod, @DecimalMin("0") BigDecimal price) {
}
