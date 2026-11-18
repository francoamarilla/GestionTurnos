package com.turnos.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

// A visitor chats with the demo assistant, then the demo resets: the AI
// tables point at the tenant, its customers and appointments, so the reset
// must clear them first or it dies on a foreign key.
@ActiveProfiles("demo")
@TestPropertySource(properties = {
    "app.demo.admin-email=demo-reset-test@example.com",
    "app.demo.admin-password=demo-password-123",
    "app.demo.seed-on-startup=false"
})
class DemoResetWithAssistantDataTest extends AbstractIntegrationTest {

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private DataSource dataSource;

    @Test
    void resetClearsConversationsDraftsAndTurns() throws Exception {
        demoDataService.reset();
        UUID first = demoDataService.demoTenantId();
        assertEquals(1, count("SELECT count(*) FROM assistant_config WHERE status = 'PUBLISHED' AND tenant_id = '"
                + first + "'"));

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            UUID customer = id(c, "SELECT id FROM customer WHERE tenant_id = '" + first + "' LIMIT 1");
            UUID appointment = id(c, "SELECT a.id FROM appointment a JOIN branch b ON b.id = a.branch_id WHERE "
                    + "b.tenant_id = '" + first + "' LIMIT 1");
            UUID conversation = id(c, "INSERT INTO conversation (tenant_id, channel, external_id, customer_id) VALUES ('"
                    + first + "', 'WEB', 'visitor', '" + customer + "') RETURNING id");
            exec(c, "INSERT INTO message (conversation_id, tenant_id, role, content) VALUES ('" + conversation + "', '"
                    + first + "', 'USER', 'hola')");
            exec(c, "INSERT INTO pending_action (tenant_id, conversation_id, type, appointment_id, summary, mode, "
                    + "expires_at) VALUES ('" + first + "', '" + conversation + "', 'CANCELLATION', '" + appointment
                    + "', 'x', 'CONFIRM', now())");
            exec(c, "INSERT INTO ai_turn (tenant_id, conversation_id, model, prompt_version, config_version, "
                    + "input_tokens, cached_tokens, output_tokens, cost_usd, latency_ms, iterations, outcome) VALUES ('"
                    + first + "', '" + conversation + "', 'm', 'p', 1, 1, 0, 1, 0.001, 10, 1, 'REPLIED')");
            exec(c, "SELECT set_config('app.is_super_admin', 'false', false)");
        }

        demoDataService.reset();

        assertNotEquals(first, demoDataService.demoTenantId());
        assertEquals(0, count("SELECT count(*) FROM conversation WHERE tenant_id = '" + first + "'"));
    }

    private int count(String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            } finally {
                exec(c, "SELECT set_config('app.is_super_admin', 'false', false)");
            }
        }
    }

    private static UUID id(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
