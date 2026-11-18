package com.turnos.agentapi;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingActionRepository extends JpaRepository<PendingAction, UUID> {

    Optional<PendingAction> findFirstByConversationIdAndStatusOrderByCreatedAtDesc(UUID conversationId, String status);
}
