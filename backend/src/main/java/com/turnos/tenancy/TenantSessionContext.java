package com.turnos.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

// Sets the Postgres session GUCs the RLS policies (V3 migration) read via
// current_setting(). See TenantContextFilter for why this is plain
// set_config(..., false) (session-scoped) rather than a request-spanning
// transaction with SET LOCAL.
@Component
public class TenantSessionContext {

    @PersistenceContext
    private EntityManager entityManager;

    public void apply(boolean isSuperAdmin, UUID tenantId) {
        setConfig("app.is_super_admin", String.valueOf(isSuperAdmin));
        setConfig("app.tenant_id", tenantId == null ? "" : tenantId.toString());
    }

    public void reset() {
        apply(false, null);
    }

    // Forces pending writes to execute now, while the GUCs this class just
    // set are still in place. Needed by callers that reset() within the same
    // transaction they wrote in (no request-scoped filter to reset only
    // after the transaction has already committed) - otherwise Hibernate's
    // deferred flush would run the INSERT/UPDATE at commit time, by which
    // point reset() has already cleared app.tenant_id, and RLS's WITH CHECK
    // rejects the write.
    public void flush() {
        entityManager.flush();
    }

    private void setConfig(String name, String value) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
                statement.setString(1, name);
                statement.setString(2, value);
                statement.execute();
            }
        });
    }
}
