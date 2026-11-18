package com.turnos.agentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.turnos.AbstractIntegrationTest;
import com.turnos.auth.JwtService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// The prepare/commit contract the agent relies on, end to end over HTTP and
// a real Postgres: the code decides AUTO vs CONFIRM, a CONFIRM draft needs
// a customer message after it, drafts don't cross conversations, and the
// owner's switches are enforced server-side.
@AutoConfigureMockMvc
class AgentBookingFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DataSource dataSource;

    private UUID tenantId;
    private UUID conversationId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            sql(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            tenantId = id(c, "INSERT INTO tenant (business_name, contact_phone, status, timezone) VALUES "
                    + "('Flow Test', '+540000000000', 'ACTIVE', 'America/Argentina/Buenos_Aires') RETURNING id");
            UUID branchId = id(c, "INSERT INTO branch (tenant_id) VALUES ('" + tenantId + "') RETURNING id");
            UUID proId = id(c, "INSERT INTO professional (branch_id, name, active) VALUES ('" + branchId
                    + "', 'Juan Pérez', true) RETURNING id");
            for (DayOfWeek day : DayOfWeek.values()) {
                sql(c, "INSERT INTO branch_schedule (branch_id, day_of_week, open_time, close_time) VALUES ('"
                        + branchId + "', '" + day + "', '00:00', '23:45')");
                sql(c, "INSERT INTO professional_schedule (professional_id, day_of_week, open_time, close_time) "
                        + "VALUES ('" + proId + "', '" + day + "', '00:00', '23:45')");
            }
            UUID serviceId = id(c, "INSERT INTO service (tenant_id, name, duration_minutes, price, active) VALUES ('"
                    + tenantId + "', 'Corte', 30, 8000, true) RETURNING id");
            sql(c, "INSERT INTO professional_service (professional_id, service_id) VALUES ('" + proId + "', '"
                    + serviceId + "')");
            conversationId = newConversation(c);
            sql(c, "SELECT set_config('app.is_super_admin', 'false', false)");
        }
    }

    @Test
    void preciseRequestIsAutoAndBooksWithBotOrigin() throws Exception {
        perform(post("/internal/agent/bookings/prepare"), prepare("corte", "Juan", "mañana", "10:30"))
                .andExpect(jsonPath("$.error").value("customer_not_identified"));

        perform(post("/internal/agent/customer/identify"), "{\"name\":\"Ana\",\"phone\":\"+54 9 11 5555-0001\"}")
                .andExpect(jsonPath("$.known").value(false));

        String draft = body(perform(post("/internal/agent/bookings/prepare"), prepare("corte", "Juan", "mañana", "10:30"))
                .andExpect(jsonPath("$.mode").value("AUTO")));

        perform(post("/internal/agent/bookings/" + JsonPath.read(draft, "$.draftId") + "/commit"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").isNotEmpty());

        assertEquals(1, count("SELECT count(*) FROM appointment a JOIN branch b ON b.id = a.branch_id "
                + "WHERE b.tenant_id = '" + tenantId + "' AND a.origin = 'BOT' AND a.customer_id IS NOT NULL"));

        // Asking for the same slot again says it's already theirs, not "taken".
        perform(post("/internal/agent/bookings/prepare"), prepare("corte", "Juan", "mañana", "10:30"))
                .andExpect(jsonPath("$.error").value("already_booked"));

        // The same draft can't be used twice.
        perform(post("/internal/agent/bookings/" + JsonPath.read(draft, "$.draftId") + "/commit"), null)
                .andExpect(status().isGone());
    }

    @Test
    void confirmDraftNeedsACustomerMessageAfterIt() throws Exception {
        identify();
        String draft = body(perform(post("/internal/agent/bookings/prepare"), prepare("corte", null, "mañana", "tipo 11"))
                .andExpect(jsonPath("$.mode").value("CONFIRM"))
                .andExpect(jsonPath("$.ambiguities[0]").value("time_approximate")));
        String commit = "/internal/agent/bookings/" + JsonPath.read(draft, "$.draftId") + "/commit";

        perform(post(commit), null).andExpect(status().isConflict());

        Thread.sleep(5); // the "sí" is strictly after the draft
        sql("INSERT INTO message (conversation_id, tenant_id, role, content) VALUES ('" + conversationId + "', '"
                + tenantId + "', 'USER', 'sí')");

        perform(post(commit), null).andExpect(status().isOk());
    }

    @Test
    void rangesAndUnknownServicesAreBlockersNotGuesses() throws Exception {
        identify();
        perform(post("/internal/agent/bookings/prepare"), prepare("corte", null, "mañana", "a la tarde"))
                .andExpect(jsonPath("$.error").value("needs_clarification"))
                .andExpect(jsonPath("$.blockers[0]").value("time_range"));
        perform(post("/internal/agent/bookings/prepare"), prepare("tintura", null, "mañana", "10"))
                .andExpect(jsonPath("$.blockers[0]").value("service_not_found"))
                .andExpect(jsonPath("$.candidates[0]").value("Corte"));
    }

    @Test
    void draftsDontCrossConversations() throws Exception {
        identify();
        String draft = body(perform(post("/internal/agent/bookings/prepare"), prepare("corte", "Juan", "mañana", "12:00")));
        UUID other;
        try (Connection c = dataSource.getConnection()) {
            sql(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            other = newConversation(c);
            sql(c, "SELECT set_config('app.is_super_admin', 'false', false)");
        }
        mockMvc.perform(post("/internal/agent/bookings/" + JsonPath.read(draft, "$.draftId") + "/commit")
                        .header("Authorization", "Bearer " + agentToken(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerSwitchIsEnforcedEvenIfTheModelCallsTheTool() throws Exception {
        String admin = jwtService.generateToken(
                UUID.randomUUID().toString(), Map.of("role", "ADMIN", "tenantId", tenantId.toString()));
        String config = "{\"assistantName\":\"Tito\",\"tone\":\"CERCANO\",\"voseo\":true,\"emojiLevel\":\"LOW\","
                + "\"canBook\":false,\"canCancel\":true,\"canReschedule\":true,\"confirmationMode\":\"STANDARD\","
                + "\"aiEnabled\":true,\"monthlyBudgetUsd\":10}";
        mockMvc.perform(put("/api/assistant/config/draft").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(config))
                .andExpect(jsonPath("$.draft.version").value(1));
        mockMvc.perform(post("/api/assistant/config/publish").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.published.assistantName").value("Tito"))
                .andExpect(jsonPath("$.draft").doesNotExist());

        identify();
        perform(post("/internal/agent/bookings/prepare"), prepare("corte", "Juan", "mañana", "10:30"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelAlwaysConfirmsAndRescheduleMovesTheBooking() throws Exception {
        identify();
        String booked = body(perform(post("/internal/agent/bookings/prepare"),
                prepare("corte", "Juan", "pasado mañana", "15:00")));
        perform(post("/internal/agent/bookings/" + JsonPath.read(booked, "$.draftId") + "/commit"), null);

        String mine = body(perform(get("/internal/agent/customer/appointments"), null)
                .andExpect(jsonPath("$.appointments.length()").value(1)));
        String appointmentId = JsonPath.read(mine, "$.appointments[0].id");

        // Reschedule, precise -> AUTO.
        String move = body(perform(post("/internal/agent/reschedules/prepare"),
                        "{\"appointmentId\":\"" + appointmentId + "\",\"dateText\":\"pasado mañana\",\"timeText\":\"16:00\"}")
                .andExpect(jsonPath("$.mode").value("AUTO")));
        String moved = body(perform(post("/internal/agent/reschedules/" + JsonPath.read(move, "$.draftId") + "/commit"), null)
                .andExpect(status().isOk()));
        String newId = JsonPath.read(moved, "$.appointmentId");

        // Cancel: always CONFIRM, so it needs the customer's yes.
        String cancel = body(perform(post("/internal/agent/cancellations/prepare"),
                        "{\"appointmentId\":\"" + newId + "\"}")
                .andExpect(jsonPath("$.mode").value("CONFIRM")));
        String commit = "/internal/agent/cancellations/" + JsonPath.read(cancel, "$.draftId") + "/commit";
        perform(post(commit), null).andExpect(status().isConflict());
        Thread.sleep(5);
        sql("INSERT INTO message (conversation_id, tenant_id, role, content) VALUES ('" + conversationId + "', '"
                + tenantId + "', 'USER', 'sí')");
        perform(post(commit), null).andExpect(jsonPath("$.cancelled").value(true));

        perform(get("/internal/agent/customer/appointments"), null)
                .andExpect(jsonPath("$.appointments.length()").value(0));
        perform(get("/internal/agent/customer/profile"), null)
                .andExpect(jsonPath("$.name").value("Ana"));
    }

    @Test
    void someoneElsesAppointmentIsNotFound() throws Exception {
        identify();
        perform(post("/internal/agent/cancellations/prepare"), "{\"appointmentId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(jsonPath("$.error").value("appointment_not_found"));
    }

    @Test
    void availabilitySearchFiltersByTimeRange() throws Exception {
        String services = body(perform(get("/internal/agent/services"), null));
        String serviceId = JsonPath.read(services, "$[0].id");
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("America/Argentina/Buenos_Aires"))
                .plusDays(1)
                .toString();
        perform(post("/internal/agent/availability/search"),
                        "{\"serviceId\":\"" + serviceId + "\",\"dateFrom\":\"" + tomorrow + "\",\"timeFrom\":\"14:00\","
                                + "\"timeTo\":\"15:00\"}")
                .andExpect(jsonPath("$.slots.length()").value(3)); // 14:00, 14:30, 15:00
    }

    @Test
    void handoffMovesTheConversationToHuman() throws Exception {
        perform(post("/internal/agent/handoff"), "{\"reason\":\"queja\"}").andExpect(status().isOk());
        assertEquals(1, count("SELECT count(*) FROM conversation WHERE id = '" + conversationId
                + "' AND mode = 'HUMAN' AND handoff_reason = 'queja'"));
    }

    // ---- helpers ----------------------------------------------------------------

    private void identify() throws Exception {
        perform(post("/internal/agent/customer/identify"), "{\"name\":\"Ana\",\"phone\":\"1155550001\"}")
                .andExpect(status().isOk());
    }

    private static String prepare(String service, String professional, String date, String time) {
        return "{\"serviceText\":\"" + service + "\","
                + (professional == null ? "" : "\"professionalText\":\"" + professional + "\",")
                + "\"dateText\":\"" + date + "\",\"timeText\":\"" + time + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request, String json)
            throws Exception {
        request.header("Authorization", "Bearer " + agentToken(conversationId));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private String agentToken(UUID conversation) {
        return jwtService.generateToken(
                conversation.toString(), Map.of("role", "AGENT", "tenantId", tenantId.toString()), 300);
    }

    private UUID newConversation(Connection c) throws Exception {
        return id(c, "INSERT INTO conversation (tenant_id, channel, external_id) VALUES ('" + tenantId
                + "', 'WEB', '" + UUID.randomUUID() + "') RETURNING id");
    }

    private void sql(String statement) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            sql(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            sql(c, statement);
            sql(c, "SELECT set_config('app.is_super_admin', 'false', false)");
        }
    }

    private int count(String query) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            sql(c, "SELECT set_config('app.is_super_admin', 'true', false)");
            try (PreparedStatement ps = c.prepareStatement(query); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            } finally {
                sql(c, "SELECT set_config('app.is_super_admin', 'false', false)");
            }
        }
    }

    private static void sql(Connection c, String statement) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            ps.execute();
        }
    }

    private static UUID id(Connection c, String statement) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(statement); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }
}
