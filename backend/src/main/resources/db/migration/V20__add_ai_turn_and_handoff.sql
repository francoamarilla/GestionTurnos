-- customer_phone: the identity the button-bot fallback keys on (it works
-- by phone, like WhatsApp). handoff_*: set when the agent or the bot passes
-- the conversation to a person (mode = HUMAN).
ALTER TABLE conversation ADD COLUMN customer_phone VARCHAR(30);
ALTER TABLE conversation ADD COLUMN handoff_reason VARCHAR(500);
ALTER TABLE conversation ADD COLUMN handoff_at TIMESTAMPTZ;

-- One row per AI turn (propuesta section 10): what the owner's dashboard
-- and the monthly budget read. Full traces stay in the AI service's logs.
CREATE TABLE ai_turn (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    conversation_id  UUID NOT NULL REFERENCES conversation(id),
    model            VARCHAR(100) NOT NULL,
    prompt_version   VARCHAR(50) NOT NULL,
    config_version   INT NOT NULL,
    input_tokens     INT NOT NULL,
    cached_tokens    INT NOT NULL,
    output_tokens    INT NOT NULL,
    cost_usd         NUMERIC(10, 6) NOT NULL,
    latency_ms       INT NOT NULL,
    iterations       INT NOT NULL,
    outcome          VARCHAR(20) NOT NULL,
    trace_id         VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_turn_tenant_created ON ai_turn(tenant_id, created_at);

ALTER TABLE ai_turn ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_turn FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_turn_isolation ON ai_turn
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
