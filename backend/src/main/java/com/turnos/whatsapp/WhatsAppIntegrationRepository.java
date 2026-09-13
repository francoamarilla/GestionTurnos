package com.turnos.whatsapp;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppIntegrationRepository extends JpaRepository<WhatsAppIntegration, UUID> {

    Optional<WhatsAppIntegration> findByTenantId(UUID tenantId);

    // Not RLS-protected (see V8 migration) - this is exactly how the webhook
    // figures out which tenant a message belongs to before any tenant
    // context exists.
    Optional<WhatsAppIntegration> findByPhoneNumber(String phoneNumber);
}
