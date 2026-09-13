package com.turnos.professionals;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProfessionalServiceAssignmentId implements Serializable {

    private UUID professionalId;
    private UUID serviceId;

    public ProfessionalServiceAssignmentId() {
    }

    public ProfessionalServiceAssignmentId(UUID professionalId, UUID serviceId) {
        this.professionalId = professionalId;
        this.serviceId = serviceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalServiceAssignmentId that)) {
            return false;
        }
        return Objects.equals(professionalId, that.professionalId) && Objects.equals(serviceId, that.serviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(professionalId, serviceId);
    }
}
