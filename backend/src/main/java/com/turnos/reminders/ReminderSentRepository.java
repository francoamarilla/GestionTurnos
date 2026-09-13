package com.turnos.reminders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReminderSentRepository extends JpaRepository<ReminderSent, UUID> {

    boolean existsByAppointmentIdAndTypeAndStatus(UUID appointmentId, ReminderType type, ReminderStatus status);

    Optional<ReminderSent> findByAppointmentIdAndType(UUID appointmentId, ReminderType type);

    // No direct tenant_id column on this table (see V10 migration) - scoped
    // by joining through the appointment's branch, same as the RLS policy.
    // Backs the dashboard's "failed reminders" alert (spec section 7); only
    // exhausted rows (attempts >= ReminderRetryPolicy.MAX_ATTEMPTS) count as
    // an alert - one still mid-retry isn't a failure yet.
    @Query(
            value = "SELECT r.* FROM reminder_sent r JOIN appointment a ON a.id = r.appointment_id "
                    + "WHERE r.status = 'FAILED' AND r.attempts >= :minAttempts AND a.branch_id = :branchId",
            nativeQuery = true)
    List<ReminderSent> findFailedByBranchId(@Param("branchId") UUID branchId, @Param("minAttempts") int minAttempts);
}
