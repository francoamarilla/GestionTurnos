package com.turnos.professionals;

// Partial update: only non-null fields are applied.
public record UpdateProfessionalRequest(String name, String contactPhone) {
}
