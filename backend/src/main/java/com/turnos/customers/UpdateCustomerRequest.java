package com.turnos.customers;

// Partial update: only non-null fields are applied.
public record UpdateCustomerRequest(String phone, String name, String internalNotes) {
}
