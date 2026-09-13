-- Ephemeral WhatsApp bot conversation state (spec section 3.1). Not a
-- source of truth for anything business-related - just enough to know what
-- step a phone number is on and what it picked so far.
CREATE TABLE bot_conversation_state (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    customer_phone  VARCHAR(50) NOT NULL,
    current_step    VARCHAR(50) NOT NULL,
    collected_data  JSONB NOT NULL DEFAULT '{}',
    expires_at      TIMESTAMPTZ NOT NULL
);

-- One active conversation per phone number per tenant (spec section 3.1: key
-- is tenantId + customerPhone).
CREATE UNIQUE INDEX idx_bot_conversation_state_tenant_phone ON bot_conversation_state(tenant_id, customer_phone);

ALTER TABLE bot_conversation_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE bot_conversation_state FORCE ROW LEVEL SECURITY;

CREATE POLICY bot_conversation_state_isolation ON bot_conversation_state
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
