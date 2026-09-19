package com.turnos.customers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import com.turnos.tenancy.TenantSessionContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

// ENDPOINTS-PENDIENTES.md #2. The aggregates, the dormant filter and the
// sorting are all SQL, so this runs against a real Postgres - a mocked
// repository would assert nothing about the part that can actually break.
//
// Fixture, all relative to now: Ana, two visits (10 and 100 days ago) plus a
// no-show; Beto, one visit a bit over 100 days ago plus a future CONFIRMED
// and an old CANCELLED one; Cata, inactive, never came; Dani, active, never
// came.
class CustomerSummaryQueryTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private TenantSessionContext tenantSessionContext;

    private UUID tenantId;

    @BeforeEach
    void setUpFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            tenantId = insertTenant(connection);
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);

            UUID ana = insertCustomer(connection, tenantId, "Ana", "+5490000000001", true);
            UUID beto = insertCustomer(connection, tenantId, "Beto", "+5490000000002", true);
            insertCustomer(connection, tenantId, "Cata", "+5490000000003", false);
            insertCustomer(connection, tenantId, "Dani", "+5490000000004", true);

            insertAppointment(connection, branchId, professionalId, serviceId, ana, "10 days", "COMPLETED", 5000);
            insertAppointment(connection, branchId, professionalId, serviceId, ana, "100 days", "COMPLETED", 7000);
            insertAppointment(connection, branchId, professionalId, serviceId, ana, "3 days", "NO_SHOW", 5000);
            // Distinct offsets on purpose: one professional plus the
            // appointment_no_overlap exclusion constraint means two rows
            // cannot share a start time.
            insertAppointment(
                    connection, branchId, professionalId, serviceId, beto, "100 days 3 hours", "COMPLETED", 9000);
            // Neither CONFIRMED nor CANCELLED is a visit, or money taken.
            insertAppointment(connection, branchId, professionalId, serviceId, beto, "-2 days", "CONFIRMED", 9000);
            insertAppointment(connection, branchId, professionalId, serviceId, beto, "40 days", "CANCELLED", 9000);
        }
        tenantSessionContext.apply(false, tenantId);
    }

    @Test
    void countsOnlyCompletedAppointmentsAsVisitsAndMoney() {
        CustomerSummaryResponse row = one(summary(null, null, null, PageRequest.of(0, 20)), "Ana");

        assertEquals(2, row.visits());
        assertEquals(1, row.noShows());
        assertEquals(0, new BigDecimal("12000").compareTo(row.spent()));
        // The latest COMPLETED one, not the no-show three days ago.
        assertEquals(10, daysAgo(row));
    }

    @Test
    void reportsZerosAndNoLastVisitForSomeoneWhoNeverCame() {
        CustomerSummaryResponse row = one(summary(null, null, null, PageRequest.of(0, 20)), "Dani");

        assertEquals(0, row.visits());
        assertEquals(0, row.noShows());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.spent()));
        assertNull(row.lastVisit());
    }

    @Test
    void dormantKeepsOnlyCustomersWhoseLastVisitIsOlderThanTheWindow() {
        Page<CustomerSummaryResponse> page = summary(null, null, 60, PageRequest.of(0, 20));

        // Beto came 100 days ago, Ana 10 days ago, and Cata and Dani never
        // came - someone who never started is not dormido.
        assertEquals(List.of("Beto"), names(page));
        assertEquals(1, page.getTotalElements());
    }

    @Test
    void filtersByActiveAndBySearch() {
        assertEquals(List.of("Cata"), names(summary(null, false, null, PageRequest.of(0, 20))));
        assertEquals(List.of("Ana", "Beto", "Dani"), names(summary(null, true, null, PageRequest.of(0, 20))));
        assertEquals(List.of("Beto"), names(summary("bet", null, null, PageRequest.of(0, 20))));
        assertEquals(List.of("Ana"), names(summary("0000001", null, null, PageRequest.of(0, 20))));
    }

    @Test
    void sortsByAnAggregateAcrossTheWholeTableNotJustThePage() {
        Sort bySpent = Sort.by(Sort.Direction.DESC, "spent");
        assertEquals(List.of("Ana"), names(summary(null, null, null, PageRequest.of(0, 1, bySpent))));
        assertEquals(List.of("Beto"), names(summary(null, null, null, PageRequest.of(1, 1, bySpent))));

        // Never-visited customers land last on a descending sort, not first.
        Sort byLastVisit = Sort.by(Sort.Direction.DESC, "lastVisit");
        List<String> ordered = names(summary(null, null, null, PageRequest.of(0, 20, byLastVisit)));
        assertEquals(List.of("Ana", "Beto"), ordered.subList(0, 2));
        assertEquals(4, ordered.size());
    }

    @Test
    void rejectsASortKeyThatIsNotOnTheWhitelist() {
        assertThrows(
                ResponseStatusException.class,
                () -> summary(null, null, null, PageRequest.of(0, 20, Sort.by("internalNotes"))));
    }

    private Page<CustomerSummaryResponse> summary(
            String search, Boolean active, Integer dormantDays, PageRequest pageable) {
        return customerService.summary(tenantId, search, active, dormantDays, pageable);
    }

    private static List<String> names(Page<CustomerSummaryResponse> page) {
        return page.getContent().stream().map(CustomerSummaryResponse::name).toList();
    }

    private static CustomerSummaryResponse one(Page<CustomerSummaryResponse> page, String name) {
        return page.getContent().stream()
                .filter(row -> name.equals(row.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " missing from " + names(page)));
    }

    private static long daysAgo(CustomerSummaryResponse row) {
        return Duration.between(row.lastVisit().toInstant(), Instant.now()).toDays();
    }

    private void setSuperAdmin(Connection connection) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO tenant (business_name, contact_phone,"
                + " status) VALUES ('Customer Summary Test', '+540000000000', 'ACTIVE') RETURNING id")) {
            return returnedId(ps);
        }
    }

    private UUID insertBranch(Connection connection, UUID tenant) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("INSERT INTO branch (tenant_id) VALUES (?) RETURNING id")) {
            ps.setObject(1, tenant);
            return returnedId(ps);
        }
    }

    private UUID insertProfessional(Connection connection, UUID branchId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO professional (branch_id, name, active) VALUES (?, 'Pro', true) RETURNING id")) {
            ps.setObject(1, branchId);
            return returnedId(ps);
        }
    }

    private UUID insertService(Connection connection, UUID tenant) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO service (tenant_id, name,"
                + " duration_minutes, price, active) VALUES (?, 'Corte', 30, 5000, true) RETURNING id")) {
            ps.setObject(1, tenant);
            return returnedId(ps);
        }
    }

    private UUID insertCustomer(Connection connection, UUID tenant, String name, String phone, boolean active)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO customer (tenant_id, name, phone, active) VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, tenant);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setBoolean(4, active);
            return returnedId(ps);
        }
    }

    // `ago` is a Postgres interval: "10 days" is in the past, "-2 days" in
    // the future.
    private void insertAppointment(
            Connection connection,
            UUID branchId,
            UUID professionalId,
            UUID serviceId,
            UUID customerId,
            String ago,
            String status,
            int price)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO appointment (branch_id, professional_id,"
                + " service_id, customer_id, start_time, end_time, duration_minutes, price, status, origin)"
                + " VALUES (?, ?, ?, ?, now() - CAST(? AS interval),"
                + " now() - CAST(? AS interval) + interval '30 minutes', 30, ?, ?, 'DASHBOARD')")) {
            ps.setObject(1, branchId);
            ps.setObject(2, professionalId);
            ps.setObject(3, serviceId);
            ps.setObject(4, customerId);
            ps.setString(5, ago);
            ps.setString(6, ago);
            ps.setInt(7, price);
            ps.setString(8, status);
            ps.execute();
        }
    }

    private static UUID returnedId(PreparedStatement ps) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "insert returned no id");
            return (UUID) rs.getObject("id");
        }
    }
}
