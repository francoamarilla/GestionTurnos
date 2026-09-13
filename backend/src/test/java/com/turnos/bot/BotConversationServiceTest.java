package com.turnos.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Exercises the bot state machine end to end against a real Postgres
// (RLS included), driving it purely through InboundMessage - the same
// abstraction a real BSP's webhook parsing will build once one is chosen
// (spec section 13). Fixtures are inserted via raw JDBC as superadmin
// (bypassing RLS), same style as AppointmentOverlapConstraintTest; assertions
// read the same way since the test's connection has no tenant context of
// its own.
class BotConversationServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BotConversationService botConversationService;

    @Test
    void newPhoneIsAskedForNameThenShownRootMenuThenBookingListsServices() throws Exception {
        String businessNumber = "+5491100000001";
        String customerPhone = "+5491100000099";
        UUID tenantId;
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            tenantId = insertTenant(connection, "Bot Test A");
            insertWhatsAppIntegration(connection, tenantId, businessNumber);
            insertService(connection, tenantId, "Corte");
        }

        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, null, null));
        assertEquals(BotStep.AWAITING_NAME, currentStep(tenantId, customerPhone));

        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, null, "Juan"));
        assertEquals(BotStep.ROOT_MENU, currentStep(tenantId, customerPhone));
        assertTrue(customerExists(tenantId, customerPhone, "Juan"));

        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, "BOOK", null));
        assertEquals(BotStep.BOOKING_SELECT_SERVICE, currentStep(tenantId, customerPhone));
    }

    @Test
    void unmatchedInputEscalatesToHumanAfterThreeFailuresThenStaysSilent() throws Exception {
        String businessNumber = "+5491100000002";
        String customerPhone = "+5491100000098";
        UUID tenantId;
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            tenantId = insertTenant(connection, "Bot Test B");
            insertWhatsAppIntegration(connection, tenantId, businessNumber);
            insertCustomer(connection, tenantId, customerPhone, "Existing");
        }

        // Already-known customer: lands straight on the root menu.
        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, null, null));
        assertEquals(BotStep.ROOT_MENU, currentStep(tenantId, customerPhone));

        for (int i = 0; i < 2; i++) {
            botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, null, "asdasd"));
            assertEquals(BotStep.ROOT_MENU, currentStep(tenantId, customerPhone));
        }
        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, null, "asdasd"));
        assertEquals(BotStep.HUMAN_HANDOFF, currentStep(tenantId, customerPhone));

        botConversationService.handleMessage(new InboundMessage(businessNumber, customerPhone, "BOOK", null));
        assertEquals(BotStep.HUMAN_HANDOFF, currentStep(tenantId, customerPhone));
    }

    private void setSuperAdmin(Connection connection) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection, String name) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tenant (business_name, contact_phone, status, cancellation_window_hours, timezone) "
                        + "VALUES (?, '+540000000000', 'ACTIVE', 2, 'America/Argentina/Buenos_Aires') RETURNING id")) {
            ps.setString(1, name);
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

    private void insertService(Connection connection, UUID tenantId, String name) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO service (tenant_id, name, duration_minutes, price, active) "
                        + "VALUES (?, ?, 30, 100, true)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, name);
            ps.execute();
        }
    }

    private void insertCustomer(Connection connection, UUID tenantId, String phone, String name) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO customer (tenant_id, phone, name, active) VALUES (?, ?, ?, true)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phone);
            ps.setString(3, name);
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

    private boolean customerExists(UUID tenantId, String phone, String name) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count(*) FROM customer WHERE tenant_id = ? AND phone = ? AND name = ?")) {
                ps.setObject(1, tenantId);
                ps.setString(2, phone);
                ps.setString(3, name);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        }
    }
}
