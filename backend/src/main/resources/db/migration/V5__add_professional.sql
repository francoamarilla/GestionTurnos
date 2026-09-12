CREATE TABLE professional (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id      UUID NOT NULL REFERENCES branch(id),
    name           VARCHAR(255) NOT NULL,
    contact_phone  VARCHAR(50),
    active         BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_professional_branch_id ON professional(branch_id);

ALTER TABLE professional ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional FORCE ROW LEVEL SECURITY;

CREATE POLICY professional_isolation ON professional
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

-- Same shape as branch_schedule (one row per day); can be a subset of the
-- branch's own hours (spec section 3.1).
CREATE TABLE professional_schedule (
    professional_id  UUID NOT NULL REFERENCES professional(id),
    day_of_week      VARCHAR(10) NOT NULL CHECK (day_of_week IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    open_time        TIME,
    close_time       TIME,
    is_closed        BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (professional_id, day_of_week)
);

ALTER TABLE professional_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_schedule FORCE ROW LEVEL SECURITY;

CREATE POLICY professional_schedule_isolation ON professional_schedule
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR professional_id IN (
            SELECT p.id FROM professional p
            JOIN branch b ON b.id = p.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR professional_id IN (
            SELECT p.id FROM professional p
            JOIN branch b ON b.id = p.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );

CREATE TABLE professional_service (
    professional_id  UUID NOT NULL REFERENCES professional(id),
    service_id       UUID NOT NULL REFERENCES service(id),
    PRIMARY KEY (professional_id, service_id)
);

ALTER TABLE professional_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_service FORCE ROW LEVEL SECURITY;

CREATE POLICY professional_service_isolation ON professional_service
    USING (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR professional_id IN (
            SELECT p.id FROM professional p
            JOIN branch b ON b.id = p.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    )
    WITH CHECK (
        COALESCE(current_setting('app.is_super_admin', true), 'false')::boolean
        OR professional_id IN (
            SELECT p.id FROM professional p
            JOIN branch b ON b.id = p.branch_id
            WHERE b.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );
