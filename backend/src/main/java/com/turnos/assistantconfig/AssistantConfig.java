package com.turnos.assistantconfig;

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

// Defaults here are what a tenant gets before the owner touches anything
// (version 0, never stored).
@Entity
@Table(name = "assistant_config")
@Getter
@Setter
@NoArgsConstructor
public class AssistantConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private int version;

    private String status;

    private String assistantName = "Asistente";

    private String tone = "CERCANO";

    private boolean voseo = true;

    private String emojiLevel = "LOW";

    private String greeting;

    private String customInstructions;

    private String policies;

    private String handoffInstructions;

    private boolean canBook = true;

    private boolean canCancel = true;

    private boolean canReschedule = true;

    private String confirmationMode = "STANDARD";

    private boolean aiEnabled = true;

    private BigDecimal monthlyBudgetUsd = BigDecimal.TEN;

    private UUID createdBy;

    private Instant createdAt = Instant.now();

    private Instant publishedAt;
}
