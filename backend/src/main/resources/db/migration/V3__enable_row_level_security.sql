-- Second layer of tenant isolation on top of the application-level tenantId
-- filtering already applied in every query (spec section 8). FORCE ROW LEVEL
-- SECURITY is required in addition to ENABLE: without it, the table owner
-- (the single "turnos" role this app connects as) would bypass RLS entirely,
-- which would make it a no-op given there's only one DB role.
--
-- app.tenant_id / app.is_super_admin are Postgres session GUCs set per
-- request by TenantContextFilter from the JWT claims. NULLIF(...,'')
-- guards against casting an unset/empty setting to uuid, which would
-- otherwise raise an error instead of just matching zero rows.
-- SuperAdmin (app.is_super_admin = 'true') bypasses tenant scoping entirely,
-- since it manages tenants/platform-wide, not a single tenant's data.
-- admin_user and super_admin are intentionally NOT covered here: admin_user
-- must be queryable by email during login, before any tenant context exists.

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

ALTER TABLE branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch FORCE ROW LEVEL SECURITY;

CREATE POLICY branch_isolation ON branch
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

ALTER TABLE branch_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch_schedule FORCE ROW LEVEL SECURITY;

CREATE POLICY branch_schedule_isolation ON branch_schedule
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch
            WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch
            WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );

ALTER TABLE branch_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch_closure FORCE ROW LEVEL SECURITY;

CREATE POLICY branch_closure_isolation ON branch_closure
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch
            WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch
            WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );
