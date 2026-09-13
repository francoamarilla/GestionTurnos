-- verification_status is a plain string (no CHECK constraint on values):
-- its exact set of values depends on the BSP, which isn't chosen yet
-- (spec section 13). Don't invent the shape.
--
-- Intentionally NOT RLS-protected, same reasoning as admin_user (see V2):
-- the webhook (spec section 11) must resolve which tenant owns a message
-- by its destination phone number BEFORE any tenant context exists - that
-- resolution *is* what establishes the tenant context for the rest of that
-- request, so RLS scoped to a tenant that isn't known yet would make this
-- table unreadable exactly when it's needed most.
CREATE TABLE whatsapp_integration (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL UNIQUE REFERENCES tenant(id),
    waba_id              VARCHAR(255),
    phone_number         VARCHAR(50),
    verification_status  VARCHAR(50)
);

CREATE INDEX idx_whatsapp_integration_phone_number ON whatsapp_integration(phone_number);
