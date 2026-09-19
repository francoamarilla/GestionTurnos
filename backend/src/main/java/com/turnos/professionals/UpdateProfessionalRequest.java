package com.turnos.professionals;

// Partial update: only non-null fields are applied. `active` is how a
// soft-deleted row comes back (DELETE /{id} only sets it false).
public record UpdateProfessionalRequest(String name, String contactPhone, Boolean active) {
}
