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
        Instant createdAt,
        // Null when no WhatsAppIntegration exists yet for this tenant. Passed
        // through verbatim (not coerced to a boolean) since verificationStatus
        // is a free string until a BSP is chosen (see WhatsAppIntegration).
        String whatsAppStatus) {

    static TenantResponse from(Tenant tenant, String whatsAppStatus) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getBusinessName(),
                tenant.getContactPhone(),
                tenant.getStatus(),
                tenant.getBusinessCategory(),
                tenant.getCancellationWindowHours(),
                tenant.getTimezone(),
                tenant.getCreatedAt(),
                whatsAppStatus);
    }
}
