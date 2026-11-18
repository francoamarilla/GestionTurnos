-- How the owner wants the AI assistant to talk and what it may do
-- (propuesta section 7). Every saved change is a new version; at most one
-- DRAFT and one PUBLISHED per tenant, older published ones are ARCHIVED.
-- Structured fields over free text: predictable and testable.
CREATE TABLE assistant_config (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenant(id),
    version               INT NOT NULL,
    status                VARCHAR(20) NOT NULL,
    assistant_name        VARCHAR(100) NOT NULL,
    tone                  VARCHAR(20) NOT NULL,
    voseo                 BOOLEAN NOT NULL DEFAULT true,
    emoji_level           VARCHAR(20) NOT NULL,
    greeting              VARCHAR(500),
    custom_instructions   TEXT CHECK (length(custom_instructions) <= 2000),
    policies              TEXT CHECK (length(policies) <= 2000),
    handoff_instructions  TEXT CHECK (length(handoff_instructions) <= 1000),
    can_book              BOOLEAN NOT NULL DEFAULT true,
    can_cancel            BOOLEAN NOT NULL DEFAULT true,
    can_reschedule        BOOLEAN NOT NULL DEFAULT true,
    confirmation_mode     VARCHAR(20) NOT NULL,
    ai_enabled            BOOLEAN NOT NULL DEFAULT true,
    monthly_budget_usd    NUMERIC(10, 2) NOT NULL DEFAULT 10,
    created_by            UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at          TIMESTAMPTZ,
    UNIQUE (tenant_id, version)
);

CREATE UNIQUE INDEX idx_assistant_config_one_draft ON assistant_config(tenant_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX idx_assistant_config_one_published ON assistant_config(tenant_id) WHERE status = 'PUBLISHED';

ALTER TABLE assistant_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE assistant_config FORCE ROW LEVEL SECURITY;

CREATE POLICY assistant_config_isolation ON assistant_config
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
