package com.turnos.appointments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Proves rule 4 (spec section 4) is enforced at the DB level, independent of
// any application code: two overlapping CONFIRMED appointments for the same
// professional are rejected by the exclusion constraint from V7, back-to-back
// slots and CANCELLED overlaps are allowed, and a different professional at
// the same time is unaffected.
class AppointmentOverlapConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void rejectsOverlappingAppointmentsForTheSameProfessional() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection);
            UUID tenantId = insertTenant(connection);
            UUID branchId = insertBranch(connection, tenantId);
            UUID professionalId = insertProfessional(connection, branchId);
            UUID otherProfessionalId = insertProfessional(connection, branchId);
            UUID serviceId = insertService(connection, tenantId);

            OffsetDateTime base = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);

            insertAppointment(connection, branchId, professionalId, serviceId, base, 30, "CONFIRMED");

            assertThrows(
                    SQLException.class,
                    () -> insertAppointment(
                            connection, branchId, professionalId, serviceId, base.plusMinutes(15), 30, "CONFIRMED"),
                    "overlapping CONFIRMED appointment for the same professional must be rejected");

            assertDoesNotThrow(
                    () -> insertAppointment(
                            connection, branchId, professionalId, serviceId, base.plusMinutes(30), 30, "CONFIRMED"),
                    "back-to-back (non-overlapping) slot must be allowed");

            assertDoesNotThrow(
                    () -> insertAppointment(
                            connection, branchId, professionalId, serviceId, base.plusMinutes(5), 10, "CANCELLED"),
                    "an overlapping CANCELLED row must not be blocked by the constraint");

            assertDoesNotThrow(
                    () -> insertAppointment(connection, branchId, otherProfessionalId, serviceId, base, 30, "CONFIRMED"),
                    "the same time slot for a different professional must not conflict");
        }
    }

    private void setSuperAdmin(Connection connection) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tenant (business_name, contact_phone, status) VALUES "
                        + "('Overlap Test', '+540000000000', 'ACTIVE') RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertBranch(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement("INSERT INTO branch (tenant_id) VALUES (?) RETURNING id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertProfessional(Connection connection, UUID branchId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO professional (branch_id, name) VALUES (?, 'Test Pro') RETURNING id")) {
            ps.setObject(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertService(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO service (tenant_id, name, duration_minutes, price) VALUES (?, 'Corte', 30, 100) "
                        + "RETURNING id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void insertAppointment(
            Connection connection,
            UUID branchId,
            UUID professionalId,
            UUID serviceId,
            OffsetDateTime startTime,
            int durationMinutes,
            String status)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO appointment (branch_id, professional_id, service_id, start_time, end_time, "
                        + "duration_minutes, price, status, origin) VALUES (?, ?, ?, ?, ?, ?, 100, ?, 'DASHBOARD')")) {
            ps.setObject(1, branchId);
            ps.setObject(2, professionalId);
            ps.setObject(3, serviceId);
            ps.setObject(4, startTime);
            ps.setObject(5, startTime.plusMinutes(durationMinutes));
            ps.setInt(6, durationMinutes);
            ps.setString(7, status);
            ps.execute();
        }
    }
}
