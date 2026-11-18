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

// role is "USER" or "ASSISTANT". Tool calls stay inside the AI service's
// turn for now; persist them when the history needs to replay them.
@Entity
@Table(name = "message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID conversationId;

    private UUID tenantId;

    private String role;

    private String content;

    private Instant createdAt = Instant.now();
}
