package com.turnos.services;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

// Partial update: only non-null fields are applied.
public record UpdateServiceRequest(
        String name, String description, @Min(1) Integer durationMinutes, @DecimalMin("0") BigDecimal price) {
}
