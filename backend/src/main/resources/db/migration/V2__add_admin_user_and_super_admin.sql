-- AdminUser is always scoped to exactly one tenant (spec section 3.1, "requerido, nunca nulo").
CREATE TABLE admin_user (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(id),
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20) NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN', 'MANAGER')),
    active         BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_admin_user_tenant_id ON admin_user(tenant_id);

-- Deliberately a separate table, not a nullable tenant_id on admin_user
-- (spec section 3.1 design note: an optional field that changes a row's
-- meaning depending on whether it's set is a common source of permission bugs).
CREATE TABLE super_admin (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL
);
