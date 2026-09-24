package com.turnos.establishment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turnos.AbstractIntegrationTest;
import com.turnos.appointments.PaymentMethod;
import com.turnos.sales.CreateSaleRequest;
import com.turnos.sales.SaleService;
import com.turnos.tenancy.TenantSessionContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

// ENDPOINTS-PENDIENTES.md #9: closing the business stops new work without
// touching the history.
class DeactivateEstablishmentTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EstablishmentService establishmentService;

    @Autowired
    private SaleService saleService;

    @Autowired
    private TenantSessionContext tenantSessionContext;

    private UUID tenantId;
    private UUID professionalId;
    private UUID adminId;

    @BeforeEach
    void setUpFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "SELECT set_config('app.is_super_admin', 'true', false)");
            tenantId = id(connection, "INSERT INTO tenant (business_name, contact_phone, status)"
                    + " VALUES ('Deactivate Test', '+540000000000', 'ACTIVE') RETURNING id");
            UUID branchId = id(connection, "INSERT INTO branch (tenant_id) VALUES ('" + tenantId + "') RETURNING id");
            professionalId = id(connection, "INSERT INTO professional (branch_id, name, active)"
                    + " VALUES ('" + branchId + "', 'Pro', true) RETURNING id");
            // completed_by is an FK to admin_user; a random UUID fails it,
            // and SaleService reports any integrity violation as an overlap.
            adminId = id(connection, "INSERT INTO admin_user (tenant_id, email, password_hash)"
                    + " VALUES ('" + tenantId + "', 'deactivate-" + tenantId + "@test', 'x') RETURNING id");
        }
        tenantSessionContext.apply(false, tenantId);
    }

    @Test
    void closingStopsOnlineBookingAndNewSales() {
        EstablishmentResponse closed = establishmentService.deactivate(tenantId);
        assertFalse(closed.onlineBookingEnabled());

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class, this::sell);
        assertEquals(409, rejected.getStatusCode().value());

        // Still readable: the history is the whole point of not deleting.
        assertEquals("Deactivate Test", establishmentService.get(tenantId).businessName());
    }

    @Test
    void closingTwiceIsRejected() {
        establishmentService.deactivate(tenantId);
        assertThrows(ResponseStatusException.class, () -> establishmentService.deactivate(tenantId));
    }

    @Test
    void sellingWorksWhileTheBusinessIsOpen() {
        assertEquals(0, new BigDecimal("1000").compareTo(sell()));
    }

    private BigDecimal sell() {
        return saleService
                .create(
                        tenantId,
                        adminId,
                        new CreateSaleRequest(
                                professionalId,
                                null,
                                "Pomada",
                                OffsetDateTime.of(2026, 4, 7, 12, 0, 0, 0, ZoneOffset.UTC),
                                new BigDecimal("1000"),
                                null,
                                null,
                                null,
                                PaymentMethod.CASH))
                .price();
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static UUID id(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject("id");
        }
    }
}
