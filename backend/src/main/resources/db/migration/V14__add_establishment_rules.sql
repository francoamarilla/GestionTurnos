-- Datos y reglas del establecimiento (ENDPOINTS-PENDIENTES.md #6).
-- booking_horizon_days NULL = sin límite. The defaults keep every existing
-- tenant behaving exactly as before this migration.
ALTER TABLE tenant ADD COLUMN address VARCHAR(200);
ALTER TABLE tenant ADD COLUMN booking_horizon_days INTEGER CHECK (booking_horizon_days > 0);
ALTER TABLE tenant ADD COLUMN slot_interval_minutes INTEGER NOT NULL DEFAULT 30
    CHECK (slot_interval_minutes IN (15, 30, 60));
ALTER TABLE tenant ADD COLUMN online_booking_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant ADD COLUMN reminders_enabled BOOLEAN NOT NULL DEFAULT true;
