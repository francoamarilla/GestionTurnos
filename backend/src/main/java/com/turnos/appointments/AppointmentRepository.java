package com.turnos.appointments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    // Any appointment (any status - the caller filters out CANCELLED, since
    // "occupies the professional's time" is a business decision, not a
    // storage concern) whose [startTime, endTime) range intersects
    // [rangeStart, rangeEnd).
    List<Appointment> findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID professionalId, Instant rangeEnd, Instant rangeStart);
}
