package com.turnos.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.appointments.PaymentMethod;
import com.turnos.statistics.StatisticsService;
import com.turnos.tenancy.TenantSessionContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

// ENDPOINTS-PENDIENTES.md #10. What voiding promises lives in the DB (the
// overlap constraint's WHERE, every total filtering COMPLETED), so this runs
// against a real Postgres.
class SaleVoidTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SaleService saleService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private TenantSessionContext tenantSessionContext;

    private UUID tenantId;
    private UUID professionalId;
    private UUID adminId;

    private final OffsetDateTime at = OffsetDateTime.of(2026, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUpFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            run(connection, "SELECT set_config('app.is_super_admin', 'true', false)");
            tenantId = id(connection, "INSERT INTO tenant (business_name, contact_phone, status)"
                    + " VALUES ('Sale Void Test', '+540000000000', 'ACTIVE') RETURNING id");
            UUID branchId = id(connection, "INSERT INTO branch (tenant_id) VALUES ('" + tenantId + "') RETURNING id");
            professionalId = id(connection, "INSERT INTO professional (branch_id, name, active)"
                    + " VALUES ('" + branchId + "', 'Pro', true) RETURNING id");
            adminId = id(connection, "INSERT INTO admin_user (tenant_id, email, password_hash)"
                    + " VALUES ('" + tenantId + "', 'void-" + tenantId + "@test', 'x') RETURNING id");
        }
        tenantSessionContext.apply(false, tenantId);
    }

    private AppointmentResponse sell(String what, int price) {
        return saleService.create(
                tenantId,
                adminId,
                new CreateSaleRequest(
                        professionalId, null, what, at, new BigDecimal(price), null, null, null, PaymentMethod.CASH));
    }

    @Test
    void voidingDropsTheSaleFromTotalsAndFreesTheTime() {
        AppointmentResponse sale = sell("Pomada", 5000);

        AppointmentResponse voided = saleService.voidSale(tenantId, adminId, sale.id(), new VoidSaleRequest(" cargada dos veces "));
        assertEquals(AppointmentStatus.VOIDED, voided.status());
        assertEquals("cargada dos veces", voided.voidReason());

        LocalDate day = at.toLocalDate();
        assertEquals(0, BigDecimal.ZERO.compareTo(statisticsService.summary(tenantId, day, day).totalSales()));

        // Same professional, same time: would be a 409 if the voided row
        // still counted for the overlap constraint.
        AppointmentResponse again = sell("Pomada", 4500);
        assertEquals(0, new BigDecimal("4500").compareTo(statisticsService.summary(tenantId, day, day).totalSales()));
        assertTrue(again.id() != null);
    }

    @Test
    void aVoidedSaleCannotBeEditedOrVoidedAgain() {
        AppointmentResponse sale = sell("Cera", 3000);
        saleService.voidSale(tenantId, adminId, sale.id(), null);

        ResponseStatusException edit = assertThrows(
                ResponseStatusException.class,
                () -> saleService.update(tenantId, sale.id(), new UpdateSaleRequest(BigDecimal.ONE, null, null, null, null)));
        assertEquals(409, edit.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> saleService.voidSale(tenantId, adminId, sale.id(), null));
    }

    @Test
    void editingChangesPricePaymentAndCustomer() {
        AppointmentResponse sale = sell("Cera", 3000);

        AppointmentResponse edited = saleService.update(
                tenantId, sale.id(), new UpdateSaleRequest(new BigDecimal("2500"), PaymentMethod.CARD, null, "Nuevo", null));

        assertEquals(0, new BigDecimal("2500").compareTo(edited.price()));
        assertEquals(PaymentMethod.CARD, edited.paymentMethod());
        assertTrue(edited.customerId() != null);
    }

    private static void run(Connection connection, String sql) throws Exception {
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
