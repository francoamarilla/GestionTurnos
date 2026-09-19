package com.turnos.customers;

// Partial update: only non-null fields are applied. `active` is how a
// soft-deleted row comes back (DELETE /{id} only sets it false).
public record UpdateCustomerRequest(String phone, String name, String internalNotes, Boolean active) {
}
