package com.turnos.reminders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.appointments.AppointmentOrigin;
import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.AbstractIntegrationTest;
import com.turnos.bot.RecordingWhatsAppProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// "Mensaje de cancelación por el negocio como plantilla" (spec section 6) -
// CancellationNotifier is what AppointmentController calls after an admin
// cancels an appointment via the dashboard.
class CancellationNotifierTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CancellationNotifier cancellationNotifier;

    @Autowired
    private RecordingWhatsAppProvider whatsAppProvider;

    @BeforeEach
    void resetProvider() {
        whatsAppProvider.reset();
    }

    @Test
    void sendsATemplateAndRecordsItExactlyOnce() throws Exception {
        UUID tenantId;
        UUID appointmentId;
        UUID customerId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, "+5491100000060");
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);
            customerId = insertCustomer(connection, tenantId, "+5491100000197");
            appointmentId = insertAppointment(connection, branchId, professionalId, serviceId, customerId);
        }

        AppointmentResponse response = appointmentResponse(appointmentId, customerId);
        cancellationNotifier.notifyBusinessCancellation(tenantId, response);
        assertEquals(1, whatsAppProvider.texts().stream().filter(t -> t.equals("CANCELLATION")).count());
        assertEquals(1, reminderCount(appointmentId));

        // A retried PATCH (client double-submit) must not notify twice.
        cancellationNotifier.notifyBusinessCancellation(tenantId, response);
        assertEquals(1, whatsAppProvider.texts().stream().filter(t -> t.equals("CANCELLATION")).count());
        assertEquals(1, reminderCount(appointmentId));
    }

    @Test
    void skipsAnonymousWalkIns() throws Exception {
        UUID tenantId;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, "+5491100000061");
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);
            appointmentId = insertAppointment(connection, branchId, professionalId, serviceId, null);
        }

        cancellationNotifier.notifyBusinessCancellation(tenantId, appointmentResponse(appointmentId, null));

        assertTrue(whatsAppProvider.texts().isEmpty());
        assertEquals(0, reminderCount(appointmentId));
    }

    private AppointmentResponse appointmentResponse(UUID appointmentId, UUID customerId) {
        return new AppointmentResponse(
                appointmentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                customerId,
                OffsetDateTime.now(ZoneOffset.UTC),
                30,
                new java.math.BigDecimal("100"),
                AppointmentStatus.CANCELLED,
                AppointmentOrigin.DASHBOARD,
                null,
                null,
                null,
                null,
                null);
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
                        + "('Cancellation Notifier Test', '+540000000000', 'ACTIVE') RETURNING id")) {
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
            Connection connection, UUID branchId, UUID professionalId, UUID serviceId, UUID customerId)
            throws Exception {
        Instant startTime = Instant.now().plusSeconds(3600);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, customer_id, start_time, "
                        + "end_time, duration_minutes, price, status, origin) VALUES "
                        + "(?, ?, ?, ?, ?::timestamptz, ?::timestamptz + interval '30 minutes', 30, 100, 'CANCELLED', 'DASHBOARD') "
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

    private int reminderCount(UUID appointmentId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count(*) FROM reminder_sent WHERE appointment_id = ? AND type = 'CANCELLATION' AND status = 'SENT'")) {
                ps.setObject(1, appointmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }
}
