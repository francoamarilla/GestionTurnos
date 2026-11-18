-- Drafts the AI agent prepares before writing anything (propuesta section 5:
-- prepare + commit). The code, not the model, decides whether the request
-- was precise (mode AUTO) or needs the customer's yes (mode CONFIRM). A
-- commit only accepts a draft that exists, is PENDING, hasn't expired and
-- belongs to the same conversation.
CREATE TABLE pending_action (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    conversation_id  UUID NOT NULL REFERENCES conversation(id),
    type             VARCHAR(20) NOT NULL,
    appointment_id   UUID REFERENCES appointment(id),
    professional_id  UUID REFERENCES professional(id),
    service_id       UUID REFERENCES service(id),
    start_time       TIMESTAMPTZ,
    summary          TEXT NOT NULL,
    mode             VARCHAR(20) NOT NULL,
    ambiguities      JSONB NOT NULL DEFAULT '[]',
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pending_action_conversation ON pending_action(conversation_id, created_at);

ALTER TABLE pending_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE pending_action FORCE ROW LEVEL SECURITY;

CREATE POLICY pending_action_isolation ON pending_action
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
