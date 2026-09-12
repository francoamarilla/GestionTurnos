package com.turnos.tenancy;

// Partial update: only non-null fields are applied. businessCategory and
// timezone are intentionally not here - businessCategory is always
// "barbershop" in the MVP and timezone is fixed (spec section 3.1).
public record UpdateTenantRequest(
        String businessName,
        String contactPhone,
        TenantStatus status,
        Integer cancellationWindowHours) {
}
