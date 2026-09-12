CREATE TABLE service (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(id),
    name              VARCHAR(255) NOT NULL,
    description       VARCHAR(500),
    duration_minutes  INTEGER NOT NULL CHECK (duration_minutes > 0),
    price             NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    active            BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_service_tenant_id ON service(tenant_id);

ALTER TABLE service ENABLE ROW LEVEL SECURITY;
ALTER TABLE service FORCE ROW LEVEL SECURITY;

CREATE POLICY service_isolation ON service
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
