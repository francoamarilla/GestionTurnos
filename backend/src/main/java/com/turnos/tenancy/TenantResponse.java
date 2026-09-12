package com.turnos.tenancy;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String businessName,
        String contactPhone,
        TenantStatus status,
        String businessCategory,
        Integer cancellationWindowHours,
        String timezone,
        Instant createdAt) {

    static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getBusinessName(),
                tenant.getContactPhone(),
                tenant.getStatus(),
                tenant.getBusinessCategory(),
                tenant.getCancellationWindowHours(),
                tenant.getTimezone(),
                tenant.getCreatedAt());
    }
}
