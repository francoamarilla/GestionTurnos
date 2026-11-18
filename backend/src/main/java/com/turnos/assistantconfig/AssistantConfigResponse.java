package com.turnos.assistantconfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AssistantConfigResponse(View published, View draft, List<VersionSummary> versions) {

    public record View(
            int version,
            String status,
            String assistantName,
            String tone,
            boolean voseo,
            String emojiLevel,
            String greeting,
            String customInstructions,
            String policies,
            String handoffInstructions,
            boolean canBook,
            boolean canCancel,
            boolean canReschedule,
            String confirmationMode,
            boolean aiEnabled,
            BigDecimal monthlyBudgetUsd) {

        public static View from(AssistantConfig c) {
            return new View(
                    c.getVersion(),
                    c.getStatus(),
                    c.getAssistantName(),
                    c.getTone(),
                    c.isVoseo(),
                    c.getEmojiLevel(),
                    c.getGreeting(),
                    c.getCustomInstructions(),
                    c.getPolicies(),
                    c.getHandoffInstructions(),
                    c.isCanBook(),
                    c.isCanCancel(),
                    c.isCanReschedule(),
                    c.getConfirmationMode(),
                    c.isAiEnabled(),
                    c.getMonthlyBudgetUsd());
        }
    }

    public record VersionSummary(int version, String status, Instant createdAt, Instant publishedAt) {
    }
}
