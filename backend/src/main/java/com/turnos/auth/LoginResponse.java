package com.turnos.auth;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        long expiresIn,
        String role,
        UUID tenantId,
        String establishmentName) {
}
