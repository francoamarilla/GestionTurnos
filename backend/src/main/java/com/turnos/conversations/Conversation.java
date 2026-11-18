package com.turnos.conversations;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// channel: WEB | PLAYGROUND (WHATSAPP later). mode: who answered last -
// AI | FALLBACK (button bot) | HUMAN (handed off; the AI stays quiet until
// the owner hands it back from the Asistente screen).
@Entity
@Table(name = "conversation")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private String channel;

    private String externalId;

    private UUID customerId;

    private String mode = "AI";

    private Instant startedAt = Instant.now();

    private Instant lastMessageAt = Instant.now();

    private String customerPhone;

    private String handoffReason;

    private Instant handoffAt;

    // The owner's test chat (Asistente screen): runs the draft config and
    // never writes real bookings.
    public boolean isPlayground() {
        return ConversationService.CHANNEL_PLAYGROUND.equals(channel);
    }
}
