package com.turnos.establishment;

import com.turnos.whatsapp.WhatsAppIntegration;
import java.util.UUID;

public record WhatsAppIntegrationResponse(UUID id, String wabaId, String phoneNumber, String verificationStatus) {

    static WhatsAppIntegrationResponse from(WhatsAppIntegration integration) {
        return new WhatsAppIntegrationResponse(
                integration.getId(),
                integration.getWabaId(),
                integration.getPhoneNumber(),
                integration.getVerificationStatus());
    }
}
