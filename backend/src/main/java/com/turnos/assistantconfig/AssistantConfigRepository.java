package com.turnos.assistantconfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantConfigRepository extends JpaRepository<AssistantConfig, UUID> {

    Optional<AssistantConfig> findByTenantIdAndStatus(UUID tenantId, String status);

    Optional<AssistantConfig> findTopByTenantIdOrderByVersionDesc(UUID tenantId);

    List<AssistantConfig> findTop20ByTenantIdOrderByVersionDesc(UUID tenantId);
}
