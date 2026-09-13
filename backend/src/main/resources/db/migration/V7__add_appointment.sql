CREATE TABLE appointment (
    id                               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id                        UUID NOT NULL REFERENCES branch(id),
    professional_id                  UUID NOT NULL REFERENCES professional(id),
    service_id                       UUID NOT NULL REFERENCES service(id),
    customer_id                      UUID REFERENCES customer(id),
    start_time                       TIMESTAMPTZ NOT NULL,
    -- Redundant with start_time + duration_minutes, but stored (not
    -- computed) on purpose: the exclusion constraint below needs
    -- tstzrange() applied to two plain columns, because Postgres's
    -- timestamptz + interval operator is STABLE, not IMMUTABLE, and can't
    -- appear in an index/exclusion-constraint expression. The app sets this
    -- alongside start_time/duration_minutes whenever it writes a row.
    end_time                         TIMESTAMPTZ NOT NULL,
    duration_minutes                 INTEGER NOT NULL CHECK (duration_minutes > 0),
    price                            NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    status                           VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    origin                           VARCHAR(20) NOT NULL CHECK (origin IN ('BOT', 'DASHBOARD', 'DIRECT_SALE')),
    rescheduled_from_appointment_id  UUID REFERENCES appointment(id),
    completed_by                     UUID REFERENCES admin_user(id),
    no_show_marked_by                UUID REFERENCES admin_user(id),
    completed_at                     TIMESTAMPTZ,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_appointment_branch_id ON appointment(branch_id);
CREATE INDEX idx_appointment_professional_id ON appointment(professional_id);
CREATE INDEX idx_appointment_customer_id ON appointment(customer_id);
CREATE INDEX idx_appointment_start_time ON appointment(start_time);

-- Spec section 4, rule 4 - the single most important integrity rule in the
-- system, "sin excepción": no two overlapping appointments for the same
-- professional, excluding CANCELLED. Enforced here, not just in application
-- code, because the backend could run more than one instance and a plain
-- "check then insert" in code is a race condition under concurrent booking.
ALTER TABLE appointment ADD CONSTRAINT appointment_no_overlap
    EXCLUDE USING gist (
        professional_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    )
    WHERE (status <> 'CANCELLED');

ALTER TABLE appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointment FORCE ROW LEVEL SECURITY;

CREATE POLICY appointment_isolation ON appointment
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR branch_id IN (
            SELECT id FROM branch WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );
