package com.turnos.whatsapp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public (permitAll in SecurityConfig), authenticated by the provider's own
// request signature rather than a JWT - spec section 11 requires this, but
// the exact signature scheme is BSP-specific (Meta's Cloud API uses HMAC-
// SHA256 over the raw body; Twilio uses a different scheme entirely) and no
// BSP is chosen yet (section 13), so no validation is wired in here. This
// is a real, currently-open gap to close before connecting a live BSP, not
// a placeholder pretending to be secure - flagging it plainly rather than
// faking a no-op check that would look like protection but isn't.
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private final WhatsAppWebhookService whatsAppWebhookService;

    public WhatsAppWebhookController(WhatsAppWebhookService whatsAppWebhookService) {
        this.whatsAppWebhookService = whatsAppWebhookService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawPayload) {
        whatsAppWebhookService.handleInbound(rawPayload);
        return ResponseEntity.status(HttpStatus.OK).build();
    }
}
