package com.turnos.professionals;

import jakarta.validation.constraints.NotBlank;

public record CreateProfessionalRequest(@NotBlank String name, String contactPhone) {
}
