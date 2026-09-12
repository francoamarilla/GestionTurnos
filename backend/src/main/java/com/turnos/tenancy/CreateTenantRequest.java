package com.turnos.tenancy;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank String businessName,
        @NotBlank String contactPhone,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminPassword) {
}
