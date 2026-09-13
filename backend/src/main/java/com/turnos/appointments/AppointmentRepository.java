package com.turnos.appointments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    // Any appointment (any status - the caller filters out CANCELLED, since
    // "occupies the professional's time" is a business decision, not a
    // storage concern) whose [startTime, endTime) range intersects
    // [rangeStart, rangeEnd).
    List<Appointment> findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID professionalId, Instant rangeEnd, Instant rangeStart);

    @Query("SELECT a FROM Appointment a WHERE a.branchId = :branchId "
            + "AND a.startTime >= :from AND a.startTime < :to "
            + "AND (:professionalId IS NULL OR a.professionalId = :professionalId) "
            + "AND (:status IS NULL OR a.status = :status) "
            + "ORDER BY a.startTime ASC")
    List<Appointment> search(
            @Param("branchId") UUID branchId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("professionalId") UUID professionalId,
            @Param("status") AppointmentStatus status);
}
