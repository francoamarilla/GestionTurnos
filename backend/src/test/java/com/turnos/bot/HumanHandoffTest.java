package com.turnos.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Spec section 5: "Hablar con alguien del local" must be reachable from any
// step, not just the ones that happen to list it as one of their options,
// and once activated the bot must stop auto-responding entirely.
class HumanHandoffTest extends AbstractIntegrationTest {

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
    void freeTextHumanRequestInterceptsAnyStepEvenMidBookingFlow() throws Exception {
        String businessNumber = "+5491100000030";
        String phone = "+5491100000095";
        UUID tenantId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, businessNumber);
            insertCustomer(connection, tenantId, phone);
            // Deep in the booking flow - "BOOKING_SELECT_SERVICE" only knows
            // how to match a tapped service id, never free text like this.
            insertConversationState(connection, tenantId, phone, "BOOKING_SELECT_SERVICE");
        }

        botConversationService.handleMessage(
                new InboundMessage(businessNumber, phone, null, "quiero hablar con una persona"));

        assertEquals(BotStep.HUMAN_HANDOFF, currentStep(tenantId, phone));
    }

    @Test
    void staysSilentOnceHandedOff() throws Exception {
        String businessNumber = "+5491100000031";
        String phone = "+5491100000096";
        UUID tenantId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, businessNumber);
            insertCustomer(connection, tenantId, phone);
            insertConversationState(connection, tenantId, phone, "HUMAN_HANDOFF");
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, "BOOK", null));

        assertEquals(BotStep.HUMAN_HANDOFF, currentStep(tenantId, phone));
        assertTrue(whatsAppProvider.texts().isEmpty(), "the bot must not send anything once handed off");
    }

    @Test
    void rootMenuAlwaysOffersTheHumanOption() throws Exception {
        String businessNumber = "+5491100000032";
        String phone = "+5491100000097";
        UUID tenantId;
        try (Connection connection = dataSource.getConnection()) {
            tenantId = insertTenant(connection);
            insertWhatsAppIntegration(connection, tenantId, businessNumber);
            insertCustomer(connection, tenantId, phone);
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, phone, null, null));

        assertTrue(whatsAppProvider.lastRows().stream().anyMatch(r -> r.id().equals("HUMAN")));
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
                        + "('Human Handoff Test', '+540000000000', 'ACTIVE') RETURNING id")) {
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

    private void insertConversationState(Connection connection, UUID tenantId, String phone, String step)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bot_conversation_state (tenant_id, customer_phone, current_step, expires_at) "
                        + "VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phone);
            ps.setString(3, step);
            ps.setObject(4, java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
            ps.execute();
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
