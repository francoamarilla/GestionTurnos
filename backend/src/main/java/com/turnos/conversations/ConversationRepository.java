package com.turnos.conversations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByTenantIdAndChannelAndExternalId(UUID tenantId, String channel, String externalId);

    List<Conversation> findByTenantIdAndChannel(UUID tenantId, String channel, Pageable pageable);

    List<Conversation> findByTenantIdAndChannelAndMode(UUID tenantId, String channel, String mode, Pageable pageable);

    // Only the mode: the agent may have changed other columns of this row
    // (handoff reason, customer) in its own requests during the turn, and
    // saving the chat request's older copy of the entity would undo them.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Conversation c SET c.mode = :mode WHERE c.id = :id")
    void updateMode(@Param("id") UUID id, @Param("mode") String mode);
}
