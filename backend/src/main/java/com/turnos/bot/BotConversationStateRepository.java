package com.turnos.bot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConversationStateRepository extends JpaRepository<BotConversationState, UUID> {

    Optional<BotConversationState> findByTenantIdAndCustomerPhone(UUID tenantId, String customerPhone);

    // Reused by the reminder job's cleanup pass (spec section 6 - "el mismo
    // job/scheduler limpia BotConversationState expirado", no separate
    // scheduler for this).
    long deleteByExpiresAtBefore(Instant cutoff);
}
