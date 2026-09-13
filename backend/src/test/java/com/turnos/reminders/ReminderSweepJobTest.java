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

// Drives ReminderSweepJob.sweep() directly (rather than waiting on
// @Scheduled) against a real Postgres, across multiple tenants at once to
// prove the per-tenant isolation in ReminderTenantSweeper holds.
class ReminderSweepJobTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReminderSweepJob reminderSweepJob;

    @Autowired
    private RecordingWhatsAppProvider whatsAppProvider;

    @BeforeEach
    void resetProvider() {
        whatsAppProvider.reset();
    }

    @Test
    void sendsAndRecordsA24hReminderExactlyOnceAcrossRepeatedSweeps() throws Exception {
        Fixture fixture;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, "+5491100000040");
            appointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(Duration.ofHours(23)), fixture.customerId());
        }

        reminderSweepJob.sweep();
        assertEquals(1, reminderCount(appointmentId, "REMINDER_24H"));
        assertEquals(0, reminderCount(appointmentId, "REMINDER_2H"));
        assertTrue(whatsAppProvider.texts().stream().anyMatch(t -> t.equals("REMINDER_24H")));

        whatsAppProvider.reset();
        reminderSweepJob.sweep();
        assertEquals(1, reminderCount(appointmentId, "REMINDER_24H"), "must not send the same reminder twice");
        assertTrue(whatsAppProvider.texts().isEmpty(), "second sweep must not re-send an already-recorded reminder");
    }

    @Test
    void sendsBothRemindersWhenAnAppointmentIsAlreadyWithinBothWindows() throws Exception {
        Fixture fixture;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, "+5491100000041");
            appointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(Duration.ofMinutes(90)), fixture.customerId());
        }

        reminderSweepJob.sweep();

        assertEquals(1, reminderCount(appointmentId, "REMINDER_24H"));
        assertEquals(1, reminderCount(appointmentId, "REMINDER_2H"));
    }

    @Test
    void skipsAppointmentsWithNoOneToNotify() throws Exception {
        Fixture fixture;
        UUID walkInAppointmentId;
        UUID phonelessAppointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, "+5491100000042");
            walkInAppointmentId =
                    insertAppointment(connection, fixture, Instant.now().plus(Duration.ofHours(1)), null);
            UUID phonelessCustomerId = insertCustomer(connection, fixture.tenantId(), null);
            phonelessAppointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(Duration.ofHours(1)).plus(Duration.ofMinutes(30)), phonelessCustomerId);
        }

        reminderSweepJob.sweep();

        assertEquals(0, reminderCount(walkInAppointmentId, "REMINDER_2H"));
        assertEquals(0, reminderCount(phonelessAppointmentId, "REMINDER_2H"));
        assertTrue(whatsAppProvider.texts().isEmpty());
    }

    @Test
    void keepsTenantsIsolatedFromEachOther() throws Exception {
        Fixture fixtureA;
        Fixture fixtureB;
        UUID appointmentA;
        UUID appointmentB;
        try (Connection connection = dataSource.getConnection()) {
            fixtureA = setUpFixture(connection, "+5491100000043");
            fixtureB = setUpFixture(connection, "+5491100000044");
            appointmentA = insertAppointment(
                    connection, fixtureA, Instant.now().plus(Duration.ofHours(1)), fixtureA.customerId());
            appointmentB = insertAppointment(
                    connection, fixtureB, Instant.now().plus(Duration.ofHours(1)), fixtureB.customerId());
        }

        reminderSweepJob.sweep();

        assertEquals(1, reminderCount(appointmentA, "REMINDER_2H"));
        assertEquals(1, reminderCount(appointmentB, "REMINDER_2H"));
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID professionalId, UUID serviceId, UUID customerId) {}

    private Fixture setUpFixture(Connection connection, String businessNumber) throws Exception {
        setSuperAdmin(connection);
        UUID tenantId = insertTenant(connection);
        insertWhatsAppIntegration(connection, tenantId, businessNumber);
        UUID branchId = insertBranch(connection, tenantId);
        UUID professionalId = insertProfessional(connection, branchId);
        UUID serviceId = insertService(connection, tenantId);
        UUID customerId = insertCustomer(connection, tenantId, "+5491100000199");
        return new Fixture(tenantId, branchId, professionalId, serviceId, customerId);
    }

    private void setSuperAdmin(Connection connection) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tenant (business_name, contact_phone, status) VALUES "
                        + "('Reminder Sweep Test', '+540000000000', 'ACTIVE') RETURNING id")) {
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

    private UUID insertAppointment(Connection connection, Fixture fixture, Instant startTime, UUID customerId)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, customer_id, start_time, "
                        + "end_time, duration_minutes, price, status, origin) VALUES "
                        + "(?, ?, ?, ?, ?::timestamptz, ?::timestamptz + interval '30 minutes', 30, 100, 'CONFIRMED', 'BOT') "
                        + "RETURNING id")) {
            ps.setObject(1, fixture.branchId());
            ps.setObject(2, fixture.professionalId());
            ps.setObject(3, fixture.serviceId());
            ps.setObject(4, customerId);
            ps.setObject(5, Timestamp.from(startTime));
            ps.setObject(6, Timestamp.from(startTime));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private int reminderCount(UUID appointmentId, String type) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count(*) FROM reminder_sent WHERE appointment_id = ? AND type = ? AND status = 'SENT'")) {
                ps.setObject(1, appointmentId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }
}
