CREATE TABLE customer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    phone           VARCHAR(50),
    name            VARCHAR(255),
    internal_notes  VARCHAR(1000),
    active          BOOLEAN NOT NULL DEFAULT true
);

-- Unique per tenant, not global (spec section 3.1); the partial index lets
-- any number of dashboard-created customers have a NULL phone.
CREATE UNIQUE INDEX idx_customer_tenant_phone ON customer(tenant_id, phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_customer_tenant_id ON customer(tenant_id);

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer FORCE ROW LEVEL SECURITY;

CREATE POLICY customer_isolation ON customer
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
