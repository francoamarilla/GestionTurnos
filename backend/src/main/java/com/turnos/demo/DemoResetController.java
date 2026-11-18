package com.turnos.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Regenerates the demo data on demand, for when a visitor has left the
// sample barbershop in a state worth resetting before the next one looks.
//
// Only exists under the "demo" profile: in any other profile this bean is
// not created, so /api/demo/reset is simply not a route and answers 404
// (SecurityConfig permits the path precisely so the 404 is the honest
// answer rather than a 401 that would confirm the endpoint exists).
//
// Authenticated by its own environment token, not a JWT: the point is to be
// callable without a demo session, and the only thing it can do is rebuild
// the demo tenant.
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoResetController {

    private final DemoDataService demoDataService;
    private final String resetToken;

    public DemoResetController(DemoDataService demoDataService, @Value("${app.demo.reset-token:}") String resetToken) {
        this.demoDataService = demoDataService;
        this.resetToken = resetToken;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@RequestHeader(name = "X-Demo-Reset-Token", required = false) String token) {
        // An unset token disables the endpoint outright. Without this, a
        // blank DEMO_RESET_TOKEN would let any caller through.
        if (resetToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (token == null || !constantTimeEquals(resetToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid reset token");
        }
        demoDataService.reset();
        return ResponseEntity.noContent().build();
    }

    // Public: the frontend's /chat/demo resolves the current demo tenant here.
    @GetMapping("/chat-tenant")
    public java.util.Map<String, java.util.UUID> chatTenant() {
        return java.util.Map.of("tenantId", demoDataService.demoTenantId());
    }

    // Avoids leaking the token one character at a time through response
    // timing. MessageDigest.isEqual is the stdlib's constant-time compare.
    private boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
