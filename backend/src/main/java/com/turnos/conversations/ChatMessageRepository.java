package com.turnos.conversations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop20ByConversationIdOrderByIdDesc(UUID conversationId);

    List<ChatMessage> findByConversationIdOrderByIdAsc(UUID conversationId);

    boolean existsByConversationIdAndRoleAndCreatedAtAfter(UUID conversationId, String role, Instant after);
}
