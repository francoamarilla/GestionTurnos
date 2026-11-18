package com.turnos.conversations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTurnRepository extends JpaRepository<AiTurn, Long> {

    @Query("SELECT COALESCE(SUM(t.costUsd), 0) FROM AiTurn t WHERE t.tenantId = :tenantId AND t.createdAt >= :since")
    BigDecimal costSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, Instant since);

    long countByTenantIdAndOutcomeAndCreatedAtGreaterThanEqual(UUID tenantId, String outcome, Instant since);
}
