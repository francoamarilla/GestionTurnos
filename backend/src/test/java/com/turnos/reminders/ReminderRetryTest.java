package com.turnos.reminders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import com.turnos.bot.RecordingWhatsAppProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// A failed send must be retried with backoff, and only becomes a dashboard-
// visible alert once retries are exhausted (spec section 6). Since
// LoggingWhatsAppProvider never fails, RecordingWhatsAppProvider's
// failTemplateSends() stands in for a real BSP rejecting a send.
class ReminderRetryTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReminderSweepJob reminderSweepJob;

    @Autowired
    private ReminderQueryService reminderQueryService;

    @Autowired
    private RecordingWhatsAppProvider whatsAppProvider;

    @BeforeEach
    void resetProvider() {
        whatsAppProvider.reset();
    }

    @Test
    void retriesWithBackoffAndOnlyAlertsOnceExhausted() throws Exception {
        UUID tenantId;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, "+5491100000050");
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);
            UUID customerId = insertCustomer(connection, tenantId, "+5491100000199");
            appointmentId = insertAppointment(
                    connection, branchId, professionalId, serviceId, customerId, Instant.now().plus(Duration.ofMinutes(90)));
        }

        whatsAppProvider.failTemplateSends(true);

        reminderSweepJob.sweep();
        assertEquals(1, attempts(appointmentId, "REMINDER_2H"));
        assertTrue(reminderQueryService.findFailed(tenantId).isEmpty(), "must not alert before exhausting retries");

        // Immediately sweeping again must NOT retry yet - still backing off.
        reminderSweepJob.sweep();
        assertEquals(1, attempts(appointmentId, "REMINDER_2H"), "must respect the backoff delay before retrying");

        // Fast-forward past the backoff window and retry up to exhaustion.
        for (int attempt = 2; attempt <= ReminderRetryPolicy.MAX_ATTEMPTS; attempt++) {
            ageLastAttempt(appointmentId, "REMINDER_2H");
            reminderSweepJob.sweep();
            assertEquals(attempt, attempts(appointmentId, "REMINDER_2H"));
        }

        assertEquals("FAILED", status(appointmentId, "REMINDER_2H"));
        assertEquals(
                1,
                reminderQueryService.findFailed(tenantId).size(),
                "exhausted retries must now show up as a dashboard alert");

        // Exhausted: sweeping again must not attempt yet another retry.
        ageLastAttempt(appointmentId, "REMINDER_2H");
        reminderSweepJob.sweep();
        assertEquals(ReminderRetryPolicy.MAX_ATTEMPTS, attempts(appointmentId, "REMINDER_2H"));
    }

    @Test
    void aLaterSuccessfulRetryMarksTheReminderSent() throws Exception {
        UUID tenantId;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, "+5491100000051");
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);
            UUID customerId = insertCustomer(connection, tenantId, "+5491100000198");
            appointmentId = insertAppointment(
                    connection, branchId, professionalId, serviceId, customerId, Instant.now().plus(Duration.ofMinutes(90)));
        }

        whatsAppProvider.failTemplateSends(true);
        reminderSweepJob.sweep();
        assertEquals("FAILED", status(appointmentId, "REMINDER_2H"));

        whatsAppProvider.failTemplateSends(false);
        ageLastAttempt(appointmentId, "REMINDER_2H");
        reminderSweepJob.sweep();

        assertEquals("SENT", status(appointmentId, "REMINDER_2H"));
        assertTrue(reminderQueryService.findFailed(tenantId).isEmpty());
    }

    private void setSuperAdmin(Connection connection) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection) throws Exception {
        setSuperAdmin(connection);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tenant (business_name, contact_phone, status) VALUES "
                        + "('Reminder Retry Test', '+540000000000', 'ACTIVE') RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void insertWhatsAppIntegration(Connection connection, UUID tenantId, String phoneNumber)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO whatsapp_integration (tenant_id, phone_number) VALUES (?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phoneNumber);
            ps.execute();
        }
    }

    private UUID insertBranch(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("INSERT INTO branch (tenant_id) VALUES (?) RETURNING id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertProfessional(Connection connection, UUID branchId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO professional (branch_id, name, active) VALUES (?, 'Test Pro', true) RETURNING id")) {
            ps.setObject(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertService(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO service (tenant_id, name, duration_minutes, price, active) "
                        + "VALUES (?, 'Corte', 30, 100, true) RETURNING id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertCustomer(Connection connection, UUID tenantId, String phone) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO customer (tenant_id, phone, name, active) VALUES (?, ?, 'Test Customer', true) RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertAppointment(
            Connection connection,
            UUID branchId,
            UUID professionalId,
            UUID serviceId,
            UUID customerId,
            Instant startTime)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, customer_id, start_time, "
                        + "end_time, duration_minutes, price, status, origin) VALUES "
                        + "(?, ?, ?, ?, ?::timestamptz, ?::timestamptz + interval '30 minutes', 30, 100, 'CONFIRMED', 'BOT') "
                        + "RETURNING id")) {
            ps.setObject(1, branchId);
            ps.setObject(2, professionalId);
            ps.setObject(3, serviceId);
            ps.setObject(4, customerId);
            ps.setObject(5, Timestamp.from(startTime));
            ps.setObject(6, Timestamp.from(startTime));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    // Pushes created_at far enough into the past that any backoff tier is
    // already elapsed, so the next sweep is guaranteed to retry.
    private void ageLastAttempt(UUID appointmentId, String type) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE reminder_sent SET created_at = now() - interval '3 hours' "
                            + "WHERE appointment_id = ? AND type = ?")) {
                ps.setObject(1, appointmentId);
                ps.setString(2, type);
                ps.execute();
            }
        }
    }

    private int attempts(UUID appointmentId, String type) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT attempts FROM reminder_sent WHERE appointment_id = ? AND type = ?")) {
                ps.setObject(1, appointmentId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected a reminder_sent row");
                    return rs.getInt(1);
                }
            }
        }
    }

    private String status(UUID appointmentId, String type) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT status FROM reminder_sent WHERE appointment_id = ? AND type = ?")) {
                ps.setObject(1, appointmentId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected a reminder_sent row");
                    return rs.getString(1);
                }
            }
        }
    }
}
