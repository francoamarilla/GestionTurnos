package com.turnos.establishment;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Self-service, Admin only (spec section 11): tenantId always comes from the
// JWT via the principal, never from the URL or request body.
@RestController
@RequestMapping("/api/establishment")
public class EstablishmentController {

    private final EstablishmentService establishmentService;

    public EstablishmentController(EstablishmentService establishmentService) {
        this.establishmentService = establishmentService;
    }

    @GetMapping
    public EstablishmentResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return establishmentService.get(principal.tenantId());
    }

    @PatchMapping
    public EstablishmentResponse update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody UpdateEstablishmentRequest request) {
        return establishmentService.update(principal.tenantId(), request);
    }

    @GetMapping("/whatsapp-integration")
    public WhatsAppIntegrationResponse whatsAppIntegration(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return establishmentService.getWhatsAppIntegration(principal.tenantId());
    }
}
