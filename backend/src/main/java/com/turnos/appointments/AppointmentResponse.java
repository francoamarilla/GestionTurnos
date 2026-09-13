package com.turnos.appointments;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

// Times are rendered in the tenant's timezone (not raw UTC), matching
// GET /api/availability - the client sends back exactly what it received.
public record AppointmentResponse(
        UUID id,
        UUID branchId,
        UUID professionalId,
        UUID serviceId,
        UUID customerId,
        OffsetDateTime startTime,
        Integer durationMinutes,
        BigDecimal price,
        AppointmentStatus status,
        AppointmentOrigin origin,
        UUID rescheduledFromAppointmentId,
        UUID completedBy,
        UUID noShowMarkedBy,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {

    static AppointmentResponse from(Appointment a, ZoneId zone) {
        return new AppointmentResponse(
                a.getId(),
                a.getBranchId(),
                a.getProfessionalId(),
                a.getServiceId(),
                a.getCustomerId(),
                a.getStartTime() == null ? null : a.getStartTime().atZone(zone).toOffsetDateTime(),
                a.getDurationMinutes(),
                a.getPrice(),
                a.getStatus(),
                a.getOrigin(),
                a.getRescheduledFromAppointmentId(),
                a.getCompletedBy(),
                a.getNoShowMarkedBy(),
                a.getCompletedAt() == null ? null : a.getCompletedAt().atZone(zone).toOffsetDateTime(),
                a.getCreatedAt() == null ? null : a.getCreatedAt().atZone(zone).toOffsetDateTime());
    }
}
