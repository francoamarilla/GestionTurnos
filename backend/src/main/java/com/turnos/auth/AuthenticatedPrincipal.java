package com.turnos.auth;

import java.util.UUID;

// The principal Spring Security holds in the SecurityContext once a JWT is
// validated. tenantId is null for SUPER_ADMIN. Read by the tenant-scoping
// interceptor added in the next commit.
public record AuthenticatedPrincipal(UUID id, String role, UUID tenantId) {
}
