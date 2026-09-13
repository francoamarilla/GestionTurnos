package com.turnos.professionals;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfessionalServiceAssignmentRepository
        extends JpaRepository<ProfessionalServiceAssignment, ProfessionalServiceAssignmentId> {

    List<ProfessionalServiceAssignment> findByProfessionalId(UUID professionalId);

    void deleteByProfessionalId(UUID professionalId);
}
