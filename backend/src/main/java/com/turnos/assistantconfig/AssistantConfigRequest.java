package com.turnos.assistantconfig;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AssistantConfigRequest(
        @NotBlank @Size(max = 100) String assistantName,
        @NotNull @Pattern(regexp = "FORMAL|CERCANO|CANCHERO") String tone,
        boolean voseo,
        @NotNull @Pattern(regexp = "NONE|LOW|HIGH") String emojiLevel,
        @Size(max = 500) String greeting,
        @Size(max = 2000) String customInstructions,
        @Size(max = 2000) String policies,
        @Size(max = 1000) String handoffInstructions,
        boolean canBook,
        boolean canCancel,
        boolean canReschedule,
        @NotNull @Pattern(regexp = "ALWAYS|STANDARD|DATE_TIME_ONLY") String confirmationMode,
        boolean aiEnabled,
        @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal monthlyBudgetUsd) {

    void applyTo(AssistantConfig c) {
        c.setAssistantName(assistantName.strip());
        c.setTone(tone);
        c.setVoseo(voseo);
        c.setEmojiLevel(emojiLevel);
        c.setGreeting(blankToNull(greeting));
        c.setCustomInstructions(blankToNull(customInstructions));
        c.setPolicies(blankToNull(policies));
        c.setHandoffInstructions(blankToNull(handoffInstructions));
        c.setCanBook(canBook);
        c.setCanCancel(canCancel);
        c.setCanReschedule(canReschedule);
        c.setConfirmationMode(confirmationMode);
        c.setAiEnabled(aiEnabled);
        c.setMonthlyBudgetUsd(monthlyBudgetUsd);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
