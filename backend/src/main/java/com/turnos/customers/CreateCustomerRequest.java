package com.turnos.customers;

// Both phone and name are optional here: this is the dashboard path. The
// bot creates customers in-process, not through this endpoint, and always
// has both (spec section 4, rule 19).
public record CreateCustomerRequest(String phone, String name, String internalNotes) {
}
