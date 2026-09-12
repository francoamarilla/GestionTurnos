package com.turnos.services;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRepository extends JpaRepository<Service, UUID> {

    // Explicit tenantId filtering here, not just RLS: spec section 8 wants
    // application-level filtering as the primary defense, RLS as the backup.
    List<Service> findByTenantId(UUID tenantId);
}
