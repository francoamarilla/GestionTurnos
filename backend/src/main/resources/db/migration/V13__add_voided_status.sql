-- Voiding a sale (ENDPOINTS-PENDIENTES.md #10). A new status rather than a
-- flag on COMPLETED: every total already filters status = 'COMPLETED', so a
-- VOIDED row drops out of Ventas, Estadísticas and the customer summary
-- without touching those queries. The row itself stays for the record.
ALTER TABLE appointment DROP CONSTRAINT appointment_status_check;
ALTER TABLE appointment ADD CONSTRAINT appointment_status_check
    CHECK (status IN ('CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'VOIDED'));

ALTER TABLE appointment ADD COLUMN void_reason VARCHAR(200);
ALTER TABLE appointment ADD COLUMN voided_by UUID REFERENCES admin_user(id);
ALTER TABLE appointment ADD COLUMN voided_at TIMESTAMPTZ;

-- A voided sale never happened as far as the schedule goes: it must not
-- keep blocking the professional's time (same treatment as CANCELLED).
ALTER TABLE appointment DROP CONSTRAINT appointment_no_overlap;
ALTER TABLE appointment ADD CONSTRAINT appointment_no_overlap
    EXCLUDE USING gist (
        professional_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    )
    WHERE (status NOT IN ('CANCELLED', 'VOIDED'));
