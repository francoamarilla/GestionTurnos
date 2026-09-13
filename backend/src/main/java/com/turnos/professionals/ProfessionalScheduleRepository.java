package com.turnos.professionals;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfessionalScheduleRepository extends JpaRepository<ProfessionalSchedule, ProfessionalScheduleId> {

    List<ProfessionalSchedule> findByProfessionalId(UUID professionalId);

    void deleteByProfessionalId(UUID professionalId);
}
