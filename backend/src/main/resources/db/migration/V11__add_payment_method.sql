-- How a sale was paid (ENDPOINTS-PENDIENTES.md #3). Nullable on purpose:
-- sales before this column, and appointments completed from Agenda/Inicio
-- (which don't ask), stay "sin dato" instead of a made-up default.
ALTER TABLE appointment
    ADD COLUMN payment_method VARCHAR(20) CHECK (payment_method IN ('CASH', 'TRANSFER', 'CARD'));
