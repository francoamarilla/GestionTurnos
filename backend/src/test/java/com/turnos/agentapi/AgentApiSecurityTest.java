package com.turnos.agentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.turnos.AbstractIntegrationTest;
import com.turnos.auth.JwtService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// The AGENT token is the only thing standing between a prompt-injected model
// and another business's data: it must see its own tenant only, and reach
// nothing outside /internal/agent/**.
@AutoConfigureMockMvc
class AgentApiSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DataSource dataSource;

    @Test
    void agentTokenSeesOnlyItsOwnTenantsServices() throws Exception {
        UUID tenantA = seedTenant("Agent A", "Corte A");
        seedTenant("Agent B", "Corte B");

        mockMvc.perform(get("/internal/agent/services").header("Authorization", "Bearer " + agentToken(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Corte A"));

        mockMvc.perform(get("/internal/agent/business-info").header("Authorization", "Bearer " + agentToken(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Agent A"));
    }

    @Test
    void agentTokenCannotReachTheAdminApiAndAdminCannotReachTheAgentApi() throws Exception {
        UUID tenant = seedTenant("Agent C", "Corte C");
        String adminToken = jwtService.generateToken(
                UUID.randomUUID().toString(), Map.of("role", "ADMIN", "tenantId", tenant.toString()));

        mockMvc.perform(get("/api/services").header("Authorization", "Bearer " + agentToken(tenant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/agent/services").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/agent/services")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicChatWithAiOffAnswersTheFallbackAndStoresBothMessages() throws Exception {
        UUID tenant = seedTenant("Agent D", "Corte D");
        String body = "{\"sessionId\":\"" + UUID.randomUUID() + "\",\"message\":\"hola\"}";

        mockMvc.perform(post("/api/public/" + tenant + "/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty());

        assertEquals(2, countMessages(tenant));
    }

    private String agentToken(UUID tenantId) {
        return jwtService.generateToken(
                UUID.randomUUID().toString(), Map.of("role", "AGENT", "tenantId", tenantId.toString()), 300);
    }

    private UUID seedTenant(String businessName, String serviceName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "SELECT set_config('app.is_super_admin', 'true', false)");
            UUID tenantId = insertReturningId(
                    connection,
                    "INSERT INTO tenant (business_name, contact_phone, status, timezone) "
                            + "VALUES (?, '+540000000000', 'ACTIVE', 'America/Argentina/Buenos_Aires') RETURNING id",
                    businessName);
            insertReturningId(connection, "INSERT INTO branch (tenant_id) VALUES (?) RETURNING id", tenantId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO service (tenant_id, name, duration_minutes, price, active) VALUES (?, ?, 30, 100, true)")) {
                ps.setObject(1, tenantId);
                ps.setString(2, serviceName);
                ps.execute();
            }
            execute(connection, "SELECT set_config('app.is_super_admin', 'false', false)");
            return tenantId;
        }
    }

    private int countMessages(UUID tenantId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "SELECT set_config('app.is_super_admin', 'true', false)");
            try (PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM message WHERE tenant_id = ?")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                execute(connection, "SELECT set_config('app.is_super_admin', 'false', false)");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static UUID insertReturningId(Connection connection, String sql, Object param) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }
}
