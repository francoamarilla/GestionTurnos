package com.turnos.professionals;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfessionalServiceAssignmentRepository
        extends JpaRepository<ProfessionalServiceAssignment, ProfessionalServiceAssignmentId> {

    List<ProfessionalServiceAssignment> findByProfessionalId(UUID professionalId);

    // Reverse lookup: which professionals offer a given service (bot booking
    // flow's "eligible professionals" step, spec section 5).
    List<ProfessionalServiceAssignment> findByServiceId(UUID serviceId);

    void deleteByProfessionalId(UUID professionalId);
}
