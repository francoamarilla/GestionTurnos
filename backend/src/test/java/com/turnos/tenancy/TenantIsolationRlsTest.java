package com.turnos.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Proves the RLS policies from V3 enforce tenant isolation on their own,
// without any WHERE tenant_id = ... in these queries - the second layer of
// defense the spec calls for (section 8), independent of whether application
// code remembers to filter.
class TenantIsolationRlsTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void tenantTableIsIsolatedByRowLevelSecurity() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection, true);
            UUID tenantA = insertTenant(connection, "Tenant A");
            UUID tenantB = insertTenant(connection, "Tenant B");

            setSuperAdmin(connection, false);
            setTenantId(connection, tenantA);
            assertEquals(1, countTenantById(connection, tenantA));
            assertEquals(0, countTenantById(connection, tenantB));

            setTenantId(connection, tenantB);
            assertEquals(1, countTenantById(connection, tenantB));
            assertEquals(0, countTenantById(connection, tenantA));

            setSuperAdmin(connection, true);
            assertTrue(countTenantById(connection, tenantA) == 1 && countTenantById(connection, tenantB) == 1);
        }
    }

    @Test
    void branchAndBranchClosureAreIsolatedByRowLevelSecurity() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setSuperAdmin(connection, true);
            UUID tenantA = insertTenant(connection, "Branch Tenant A");
            UUID tenantB = insertTenant(connection, "Branch Tenant B");
            UUID branchA = insertBranch(connection, tenantA);
            UUID branchB = insertBranch(connection, tenantB);
            insertBranchClosure(connection, branchA);
            insertBranchClosure(connection, branchB);

            setSuperAdmin(connection, false);
            setTenantId(connection, tenantA);
            assertEquals(1, countRows(connection, "branch"));
            assertEquals(1, countRows(connection, "branch_closure"));

            setTenantId(connection, tenantB);
            assertEquals(1, countRows(connection, "branch"));
            assertEquals(1, countRows(connection, "branch_closure"));
        }
    }

    private void setSuperAdmin(Connection connection, boolean value) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.is_super_admin', ?, false)")) {
            ps.setString(1, String.valueOf(value));
            ps.execute();
        }
    }

    private void setTenantId(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    private UUID insertTenant(Connection connection, String name) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tenant (business_name, contact_phone, status) VALUES (?, '+540000000000', 'ACTIVE') RETURNING id")) {
            ps.setString(1, name);
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

    private void insertBranchClosure(Connection connection, UUID branchId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO branch_closure (branch_id, date) VALUES (?, CURRENT_DATE)")) {
            ps.setObject(1, branchId);
            ps.execute();
        }
    }

    private int countTenantById(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM tenant WHERE id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRows(Connection connection, String table) throws Exception {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
