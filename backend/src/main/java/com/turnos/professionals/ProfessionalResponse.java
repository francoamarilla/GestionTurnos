package com.turnos.professionals;

import java.util.UUID;

public record ProfessionalResponse(UUID id, String name, String contactPhone, boolean active) {

    static ProfessionalResponse from(Professional professional) {
        return new ProfessionalResponse(
                professional.getId(), professional.getName(), professional.getContactPhone(), professional.isActive());
    }
}
