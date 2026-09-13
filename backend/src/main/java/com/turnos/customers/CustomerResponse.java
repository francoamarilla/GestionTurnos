package com.turnos.customers;

import java.util.UUID;

public record CustomerResponse(UUID id, String phone, String name, String internalNotes, boolean active) {

    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getPhone(),
                customer.getName(),
                customer.getInternalNotes(),
                customer.isActive());
    }
}
