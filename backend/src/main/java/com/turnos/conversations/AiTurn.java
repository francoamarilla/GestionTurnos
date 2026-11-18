package com.turnos.conversations;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// outcome: REPLIED | BOOKED | CANCELLED | RESCHEDULED | HANDOFF | FALLBACK | ERROR
@Entity
@Table(name = "ai_turn")
@Getter
@Setter
@NoArgsConstructor
public class AiTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID tenantId;

    private UUID conversationId;

    private String model;

    private String promptVersion;

    private int configVersion;

    private int inputTokens;

    private int cachedTokens;

    private int outputTokens;

    private BigDecimal costUsd = BigDecimal.ZERO;

    private int latencyMs;

    private int iterations;

    private String outcome;

    private String traceId;

    private Instant createdAt = Instant.now();
}
