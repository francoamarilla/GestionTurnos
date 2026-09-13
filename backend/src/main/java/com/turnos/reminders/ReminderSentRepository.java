package com.turnos.reminders;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderSentRepository extends JpaRepository<ReminderSent, UUID> {

    boolean existsByAppointmentIdAndTypeAndStatus(UUID appointmentId, ReminderType type, ReminderStatus status);

    Optional<ReminderSent> findByAppointmentIdAndType(UUID appointmentId, ReminderType type);
}
