package com.turnos.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import com.turnos.whatsapp.WhatsAppProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Drives the booking flow (spec section 5) end to end against a real
// Postgres, using RecordingWhatsAppProvider to read back the actual
// service/professional/slot ids the bot offered at each step - these are
// generated UUIDs / availability-grid output the test can't predict up
// front.
class BookingFlowTest extends AbstractIntegrationTest {

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
    void confirmsABookingEndToEnd() throws Exception {
        String businessNumber = "+5491100000010";
        String phone = "+5491100000090";
        Fixture fixture;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, businessNumber, phone);
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));
        assertEquals(BotStep.ROOT_MENU, currentStep(fixture.tenantId(), phone));

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "BOOK", null));
        assertEquals(BotStep.BOOKING_SELECT_SERVICE, currentStep(fixture.tenantId(), phone));
        // The one active service + the always-visible "Hablar con alguien" row.
        assertEquals(2, whatsAppProvider.lastRows().size());
        String serviceRowId = whatsAppProvider.lastRows().get(0).id();
        assertEquals(fixture.serviceId().toString(), serviceRowId);

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, serviceRowId, null));
        assertEquals(BotStep.BOOKING_SELECT_PROFESSIONAL, currentStep(fixture.tenantId(), phone));
        // "Cualquiera disponible" + the one eligible professional + "Hablar con alguien".
        assertEquals(3, whatsAppProvider.lastRows().size());
        String professionalRowId = whatsAppProvider.lastRows().get(1).id();
        assertEquals(fixture.professionalId().toString(), professionalRowId);

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, professionalRowId, null));
        assertEquals(BotStep.BOOKING_SELECT_SLOT, currentStep(fixture.tenantId(), phone));
        assertFalse(whatsAppProvider.lastRows().isEmpty(), "expected at least one open slot in a wide-open schedule");
        String slotRowId = whatsAppProvider.lastRows().get(0).id();

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, slotRowId, null));
        assertEquals(BotStep.BOOKING_CONFIRM, currentStep(fixture.tenantId(), phone));
        // Confirmar / Cancelar / "Hablar con alguien".
        assertEquals(3, whatsAppProvider.lastButtons().size());

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "CONFIRM_BOOKING", null));
        assertEquals(BotStep.ROOT_MENU, currentStep(fixture.tenantId(), phone));
        assertEquals(1, countConfirmedAppointments(fixture.professionalId()));
    }

    @Test
    void reoffersUpdatedSlotsWhenTheChosenOneWasTakenInTheInterim() throws Exception {
        String businessNumber = "+5491100000011";
        String phone = "+5491100000091";
        Fixture fixture;
        try (Connection connection = dataSource.getConnection()) {
            fixture = setUpFixture(connection, businessNumber, phone);
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "BOOK", null));
        String serviceRowId = whatsAppProvider.lastRows().get(0).id();
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, serviceRowId, null));
        String professionalRowId = whatsAppProvider.lastRows().get(1).id();
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, professionalRowId, null));
        String slotRowId = whatsAppProvider.lastRows().get(0).id();
        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, slotRowId, null));
        assertEquals(BotStep.BOOKING_CONFIRM, currentStep(fixture.tenantId(), phone));

        // Someone else grabs that exact slot right before the customer confirms.
        String[] parts = slotRowId.split("\\|", 2);
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            insertAppointment(connection, fixture.branchId(), UUID.fromString(parts[0]), fixture.serviceId(), parts[1]);
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "CONFIRM_BOOKING", null));
        assertEquals(
                BotStep.BOOKING_SELECT_SLOT,
                currentStep(fixture.tenantId(), phone),
                "a losing race should re-offer slots, not silently fail or duplicate");
        assertTrue(
                whatsAppProvider.lastRows().stream().noneMatch(r -> r.id().equals(slotRowId)),
                "the now-taken slot must not be offered again");
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID professionalId, UUID serviceId) {}

    private Fixture setUpFixture(Connection connection, String businessNumber, String phone) throws Exception {
        setSuperAdmin(connection);
        UUID tenantId = insertTenant(connection);
        insertWhatsAppIntegration(connection, tenantId, businessNumber);
        insertCustomer(connection, tenantId, phone);
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
        return new Fixture(tenantId, branchId, professionalId, serviceId);
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
                        + "('Booking Flow Test', '+540000000000', 'ACTIVE') RETURNING id")) {
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

    private void insertCustomer(Connection connection, UUID tenantId, String phone) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO customer (tenant_id, phone, name, active) VALUES (?, ?, 'Existing', true)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phone);
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

    private void insertBranchSchedule(Connection connection, UUID branchId, DayOfWeek day) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO branch_schedule (branch_id, day_of_week, open_time, close_time) "
                        + "VALUES (?, ?, '00:00', '23:45')")) {
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
                "INSERT INTO professional_schedule (professional_id, day_of_week, open_time, close_time) "
                        + "VALUES (?, ?, '00:00', '23:45')")) {
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

    private void insertAppointment(
            Connection connection, UUID branchId, UUID professionalId, UUID serviceId, String startTimeIso)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, start_time, end_time, "
                        + "duration_minutes, price, status, origin) VALUES "
                        + "(?, ?, ?, ?::timestamptz, ?::timestamptz + interval '30 minutes', 30, 100, 'CONFIRMED', 'DASHBOARD')")) {
            ps.setObject(1, branchId);
            ps.setObject(2, professionalId);
            ps.setObject(3, serviceId);
            ps.setString(4, startTimeIso);
            ps.setString(5, startTimeIso);
            ps.execute();
        }
    }

    private int countConfirmedAppointments(UUID professionalId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count(*) FROM appointment WHERE professional_id = ? AND status = 'CONFIRMED'")) {
                ps.setObject(1, professionalId);
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
