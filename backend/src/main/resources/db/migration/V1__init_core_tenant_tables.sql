-- Enables the GiST index support needed later for the appointment
-- non-overlap exclusion constraint (see spec section 4, rule 4).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE tenant (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name              VARCHAR(255) NOT NULL,
    contact_phone              VARCHAR(50) NOT NULL,
    status                     VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TRIAL')),
    business_category          VARCHAR(50) NOT NULL DEFAULT 'barbershop',
    cancellation_window_hours  INTEGER NOT NULL DEFAULT 2,
    timezone                   VARCHAR(100) NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Modeled as its own table (rather than folded into tenant) even though the
-- MVP always creates exactly one branch per tenant with no management screen,
-- so multi-branch support later is a data migration, not a schema redesign.
CREATE TABLE branch (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenant(id),
    address    VARCHAR(255)
);

CREATE INDEX idx_branch_tenant_id ON branch(tenant_id);

-- One row per day of week; a split morning/afternoon range is explicitly
-- out of scope until confirmed (spec section 13).
CREATE TABLE branch_schedule (
    branch_id    UUID NOT NULL REFERENCES branch(id),
    day_of_week  VARCHAR(10) NOT NULL CHECK (day_of_week IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    open_time    TIME,
    close_time   TIME,
    is_closed    BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (branch_id, day_of_week)
);

CREATE TABLE branch_closure (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id  UUID NOT NULL REFERENCES branch(id),
    date       DATE NOT NULL,
    reason     VARCHAR(255)
);

CREATE INDEX idx_branch_closure_branch_id ON branch_closure(branch_id);
