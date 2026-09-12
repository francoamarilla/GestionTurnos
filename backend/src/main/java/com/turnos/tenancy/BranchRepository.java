package com.turnos.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    // Exactly one branch per tenant in the MVP (spec section 3.1).
    Optional<Branch> findByTenantId(UUID tenantId);
}
