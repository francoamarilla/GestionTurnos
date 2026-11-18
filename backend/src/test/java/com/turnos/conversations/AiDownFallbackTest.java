package com.turnos.conversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// The AI is configured but down: every message still gets an answer from
// the button bot, the message itself reaches the bot (the phone it asked
// for isn't lost), and the failed turn is recorded.
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.ai.base-url=http://127.0.0.1:9", "app.ai.service-token=x"})
class AiDownFallbackTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aiDownFallsBackWithoutLosingTheMessage() throws Exception {
        UUID tenant;
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO tenant (business_name, contact_phone, status, timezone) VALUES "
                                    + "('AI Down', '+540000000000', 'ACTIVE', 'America/Argentina/Buenos_Aires') RETURNING id");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                tenant = (UUID) rs.getObject(1);
            }
            exec(c, "INSERT INTO branch (tenant_id) VALUES ('" + tenant + "')");
            exec(c, "INSERT INTO customer (tenant_id, phone, name, active) VALUES ('" + tenant
                    + "', '1155550077', 'Existing', true)");
            exec(c, "SELECT set_config('app.is_super_admin', 'false', false)");
        }
        UUID session = UUID.randomUUID();

        chat(tenant, session, "hola").andExpect(jsonPath("$.reply").value(ConversationService.ASK_PHONE));
        chat(tenant, session, "1155550077")
                .andExpect(jsonPath("$.mode").value("FALLBACK"))
                .andExpect(jsonPath("$.options.length()").value(Matchers.greaterThan(0)));

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            try (PreparedStatement ps = c.prepareStatement(
                            "SELECT count(*) FROM ai_turn WHERE tenant_id = '" + tenant + "' AND outcome = 'ERROR'");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            } finally {
                exec(c, "SELECT set_config('app.is_super_admin', 'false', false)");
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions chat(UUID tenant, UUID session, String message)
            throws Exception {
        return mockMvc.perform(post("/api/public/" + tenant + "/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + session + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk());
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
