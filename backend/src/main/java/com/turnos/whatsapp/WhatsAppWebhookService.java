package com.turnos.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// Processes the webhook body off the request thread, so the controller can
// ack 200 immediately (spec section 11). Payload parsing (extracting the
// destination number to resolve the tenant via
// WhatsAppIntegrationRepository.findByPhoneNumber, the sender, the message
// content) is deliberately NOT implemented here: the exact JSON shape
// depends on the BSP, which isn't chosen yet (spec section 13) - "no
// inventarlo". This just proves the async, fire-and-forget pipeline works;
// a real BSP's shape gets parsed here once one is picked.
@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);

    @Async
    public void handleInbound(String rawPayload) {
        log.info("[WhatsApp webhook] received payload ({} bytes), shape pending BSP choice: {}",
                rawPayload == null ? 0 : rawPayload.length(), rawPayload);
    }
}
