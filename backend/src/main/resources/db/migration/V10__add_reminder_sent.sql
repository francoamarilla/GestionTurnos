-- Idempotency ledger for reminders/notifications (spec section 3.1). No
-- tenant_id column - same convention as professional_schedule/appointment
-- itself: isolation is resolved by joining up to the owning tenant through
-- the entity this table actually hangs off (appointment -> branch -> tenant).
CREATE TABLE reminder_sent (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id  UUID NOT NULL REFERENCES appointment(id),
    type            VARCHAR(20) NOT NULL CHECK (type IN ('CONFIRMATION', 'REMINDER_24H', 'REMINDER_2H', 'CANCELLATION')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The actual idempotency guarantee (spec section 3.1): a given
-- appointment+type can never have more than one successful send.
CREATE UNIQUE INDEX idx_reminder_sent_appointment_type_sent
    ON reminder_sent(appointment_id, type) WHERE status = 'SENT';
CREATE INDEX idx_reminder_sent_appointment_id ON reminder_sent(appointment_id);

ALTER TABLE reminder_sent ENABLE ROW LEVEL SECURITY;
ALTER TABLE reminder_sent FORCE ROW LEVEL SECURITY;

CREATE POLICY reminder_sent_isolation ON reminder_sent
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR appointment_id IN (
            SELECT a.id FROM appointment a
            JOIN branch b ON b.id = a.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR appointment_id IN (
            SELECT a.id FROM appointment a
            JOIN branch b ON b.id = a.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );
