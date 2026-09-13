package com.turnos.bot;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConversationStateRepository extends JpaRepository<BotConversationState, UUID> {

    Optional<BotConversationState> findByTenantIdAndCustomerPhone(UUID tenantId, String customerPhone);
}
