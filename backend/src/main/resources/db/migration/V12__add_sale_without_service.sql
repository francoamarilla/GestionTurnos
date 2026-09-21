-- A sale of something not in the catalog ("Otro", ENDPOINTS-PENDIENTES.md
-- #5): no service_id, a free-text description instead. Every row still has
-- one of the two, so nothing downstream shows a nameless line.
ALTER TABLE appointment ALTER COLUMN service_id DROP NOT NULL;
ALTER TABLE appointment ADD COLUMN description VARCHAR(120);
ALTER TABLE appointment
    ADD CONSTRAINT appointment_service_or_description
    CHECK (service_id IS NOT NULL OR description IS NOT NULL);
