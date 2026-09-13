package com.turnos.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Drives the view/cancel/reschedule flow (spec section 5) end to end
// against a real Postgres: identify -> list upcoming CONFIRMED appointments
// -> pick one -> cancel (inside and outside the cancellation window) or
// reschedule (reuses the booking slot-selection steps).
class ViewCancelRescheduleFlowTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BotConversationService botConversationService;

    @Autowired
    private RecordingWhatsAppProvider whatsAppProvider;

    @BeforeEach
    void resetProvider() {
        whatsAppProvider.reset();
    }

    @Test
    void cancelsAnAppointmentWithinTheWindow() throws Exception {
        String businessNumber = "+5491100000020";
        String phone = "+5491100000092";
        Fixture fixture;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, businessNumber, phone);
            appointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(java.time.Duration.ofDays(1)));
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "VIEW", null));
        assertEquals(BotStep.VIEWING_APPOINTMENTS, currentStep(fixture.tenantId(), phone));
        assertEquals(1, whatsAppProvider.lastRows().size());
        assertEquals(appointmentId.toString(), whatsAppProvider.lastRows().get(0).id());

        botConversationService.handleMessage(
                new InboundMessage(businessNumber, phone, appointmentId.toString(), null));
        assertEquals(BotStep.APPOINTMENT_ACTION, currentStep(fixture.tenantId(), phone));
        assertEquals(2, whatsAppProvider.lastButtons().size());

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "CANCEL_APPT", null));
        assertEquals(BotStep.ROOT_MENU, currentStep(fixture.tenantId(), phone));
        assertEquals("CANCELLED", appointmentStatus(appointmentId));
    }

    @Test
    void refusesToCancelOutsideTheWindowAndExplainsThePolicy() throws Exception {
        String businessNumber = "+5491100000021";
        String phone = "+5491100000093";
        Fixture fixture;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, businessNumber, phone);
            // Tenant's default cancellationWindowHours is 2 (V1 migration);
            // 30 minutes out is inside that window.
            appointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(java.time.Duration.ofMinutes(30)));
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "VIEW", null));
        botConversationService.handleMessage(
                new InboundMessage(businessNumber, phone, appointmentId.toString(), null));

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "CANCEL_APPT", null));
        assertEquals(BotStep.ROOT_MENU, currentStep(fixture.tenantId(), phone));
        assertEquals(
                "CONFIRMED",
                appointmentStatus(appointmentId),
                "an appointment inside the cancellation window must not be cancelled");
        assertTrue(whatsAppProvider.texts().stream().anyMatch(t -> t.contains("ventana")));
    }

    @Test
    void reschedulesAnAppointmentToANewSlot() throws Exception {
        String businessNumber = "+5491100000022";
        String phone = "+5491100000094";
        Fixture fixture;
        UUID appointmentId;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, businessNumber, phone);
            appointmentId = insertAppointment(
                    connection, fixture, Instant.now().plus(java.time.Duration.ofDays(1)));
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "VIEW", null));
        botConversationService.handleMessage(
                new InboundMessage(businessNumber, phone, appointmentId.toString(), null));

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "RESCHEDULE_APPT", null));
        assertEquals(BotStep.BOOKING_SELECT_SLOT, currentStep(fixture.tenantId(), phone));
        assertTrue(whatsAppProvider.lastRows().stream().allMatch(r -> r.id().startsWith(fixture.professionalId() + "|")));
        String slotRowId = whatsAppProvider.lastRows().get(0).id();

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, slotRowId, null));
        assertEquals(BotStep.BOOKING_CONFIRM, currentStep(fixture.tenantId(), phone));

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "CONFIRM_BOOKING", null));
        assertEquals(BotStep.ROOT_MENU, currentStep(fixture.tenantId(), phone));
        assertEquals("CANCELLED", appointmentStatus(appointmentId));
        assertEquals(1, countReplacementAppointments(appointmentId));
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID professionalId, UUID serviceId, UUID customerId) {}

    private Fixture setUpFixture(Connection connection, String businessNumber, String phone) throws Exception {
        setSuperAdmin(connection);
        UUID tenantId = insertTenant(connection);
        insertWhatsAppIntegration(connection, tenantId, businessNumber);
        UUID customerId = insertCustomer(connection, tenantId, phone);
        UUID branchId = insertBranch(connection, tenantId);
        for (DayOfWeek day : DayOfWeek.values()) {
            insertBranchSchedule(connection, branchId, day);
        }
        UUID professionalId = insertProfessional(connection, branchId);
        for (DayOfWeek day : DayOfWeek.values()) {
            insertProfessionalSchedule(connection, professionalId, day);
        }
        UUID serviceId = insertService(connection, tenantId);
        insertProfessionalService(connection, professionalId, serviceId);
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
                        + "('View Cancel Reschedule Test', '+540000000000', 'ACTIVE') RETURNING id")) {
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

    private UUID insertCustomer(Connection connection, UUID tenantId, String phone) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO customer (tenant_id, phone, name, active) VALUES (?, ?, 'Existing', true) RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
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

    private void insertBranchSchedule(Connection connection, UUID branchId, DayOfWeek day) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO branch_schedule (branch_id, day_of_week, open_time, close_time, is_closed) "
                        + "VALUES (?, ?, '00:00', '23:45', false)")) {
            ps.setObject(1, branchId);
            ps.setString(2, day.name());
            ps.execute();
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

    private void insertProfessionalSchedule(Connection connection, UUID professionalId, DayOfWeek day)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO professional_schedule (professional_id, day_of_week, open_time, close_time, is_closed) "
                        + "VALUES (?, ?, '00:00', '23:45', false)")) {
            ps.setObject(1, professionalId);
            ps.setString(2, day.name());
            ps.execute();
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

    private void insertProfessionalService(Connection connection, UUID professionalId, UUID serviceId)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO professional_service (professional_id, service_id) VALUES (?, ?)")) {
            ps.setObject(1, professionalId);
            ps.setObject(2, serviceId);
            ps.execute();
        }
    }

    private UUID insertAppointment(Connection connection, Fixture fixture, Instant startTime) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, customer_id, start_time, "
                        + "end_time, duration_minutes, price, status, origin) VALUES "
                        + "(?, ?, ?, ?, ?, ?::timestamptz + interval '30 minutes', 30, 100, 'CONFIRMED', 'BOT') RETURNING id")) {
            ps.setObject(1, fixture.branchId());
            ps.setObject(2, fixture.professionalId());
            ps.setObject(3, fixture.serviceId());
            ps.setObject(4, fixture.customerId());
            ps.setObject(5, java.sql.Timestamp.from(startTime));
            ps.setObject(6, java.sql.Timestamp.from(startTime));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String appointmentStatus(UUID appointmentId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement("SELECT status FROM appointment WHERE id = ?")) {
                ps.setObject(1, appointmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString("status");
                }
            }
        }
    }

    private int countReplacementAppointments(UUID originalAppointmentId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count(*) FROM appointment WHERE rescheduled_from_appointment_id = ? AND status = 'CONFIRMED'")) {
                ps.setObject(1, originalAppointmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private BotStep currentStep(UUID tenantId, String phone) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT current_step FROM bot_conversation_state WHERE tenant_id = ? AND customer_phone = ?")) {
                ps.setObject(1, tenantId);
                ps.setString(2, phone);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected a bot_conversation_state row for " + phone);
                    return BotStep.valueOf(rs.getString("current_step"));
                }
            }
        }
    }
}
