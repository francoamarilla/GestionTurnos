package com.turnos.conversations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// With the AI off (no AI_SERVICE_URL in tests) the web chat falls back to
// the existing button bot (propuesta D4): it asks for a phone - the bot's
// identity - then runs the same state machine WhatsApp uses, with its
// buttons returned in the HTTP response.
@AutoConfigureMockMvc
class WebFallbackTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aiOffRunsTheButtonBotOverTheWebChat() throws Exception {
        UUID tenant = seedTenant();
        UUID session = UUID.randomUUID();

        chat(tenant, session, "\"message\":\"hola, quiero un turno\"")
                .andExpect(jsonPath("$.mode").value("FALLBACK"))
                .andExpect(jsonPath("$.reply").value(ConversationService.ASK_PHONE));

        // Known customer -> straight to the menu, with buttons.
        chat(tenant, session, "\"message\":\"11 5555-0009\"")
                .andExpect(jsonPath("$.options.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        // "Hablar con alguien" -> the conversation is handed to a person.
        chat(tenant, session, "\"selectionId\":\"HUMAN\"").andExpect(jsonPath("$.mode").value("HUMAN"));
        chat(tenant, session, "\"message\":\"¿hola?\"")
                .andExpect(jsonPath("$.reply").value(ConversationService.HUMAN_NOTICE));

        // The screen can show the whole thread, in order; other sessions see nothing.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/public/" + tenant + "/chat/history").param("sessionId", session.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("hola, quiero un turno"))
                .andExpect(jsonPath("$[7].content").value(ConversationService.HUMAN_NOTICE));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/public/" + tenant + "/chat/history").param("sessionId", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void emptyMessageIsRejected() throws Exception {
        UUID tenant = seedTenant();
        mockMvc.perform(post("/api/public/" + tenant + "/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + UUID.randomUUID() + "\",\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions chat(UUID tenant, UUID session, String fields)
            throws Exception {
        return mockMvc.perform(post("/api/public/" + tenant + "/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + session + "\"," + fields + "}"))
                .andExpect(status().isOk());
    }

    private UUID seedTenant() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            UUID tenant;
            try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO tenant (business_name, contact_phone, status, timezone) VALUES "
                                    + "('Fallback Test', '+540000000000', 'ACTIVE', 'America/Argentina/Buenos_Aires') "
                                    + "RETURNING id");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                tenant = (UUID) rs.getObject(1);
            }
            exec(c, "INSERT INTO branch (tenant_id) VALUES ('" + tenant + "')");
            exec(c, "INSERT INTO customer (tenant_id, phone, name, active) VALUES ('" + tenant
                    + "', '1155550009', 'Existing', true)");
            exec(c, "SELECT set_config('app.is_super_admin', 'false', false)");
            return tenant;
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
