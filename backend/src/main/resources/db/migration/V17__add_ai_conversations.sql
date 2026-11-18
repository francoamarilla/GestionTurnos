-- AI assistant conversations (propuesta-arquitectura-ia.md, section 11.2).
-- One open conversation per channel identity (web session id, later a
-- WhatsApp phone). mode decides who answers: the AI agent, the button bot
-- fallback, or a person.
CREATE TABLE conversation (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    channel          VARCHAR(20) NOT NULL,
    external_id      VARCHAR(100) NOT NULL,
    customer_id      UUID REFERENCES customer(id),
    mode             VARCHAR(20) NOT NULL DEFAULT 'AI',
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel, external_id)
);

CREATE TABLE message (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES conversation(id),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    role             VARCHAR(20) NOT NULL,
    content          TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_conversation ON message(conversation_id, id);

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_isolation ON conversation
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

ALTER TABLE message ENABLE ROW LEVEL SECURITY;
ALTER TABLE message FORCE ROW LEVEL SECURITY;

CREATE POLICY message_isolation ON message
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
