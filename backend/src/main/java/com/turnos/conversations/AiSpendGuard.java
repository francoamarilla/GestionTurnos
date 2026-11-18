package com.turnos.conversations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Global daily LLM spend cap across every tenant - the public demo's last
// line of defence against a stranger burning the API key. Per-tenant
// monthly budgets are separate (assistant_config.monthly_budget_usd).
// ponytail: in memory, so a restart resets today's count and two instances
// would each allow the full cap; move to a DB sum if either matters.
@Component
public class AiSpendGuard {

    private final BigDecimal dailyCapUsd;
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private BigDecimal spentToday = BigDecimal.ZERO;

    public AiSpendGuard(@Value("${app.ai.daily-budget-usd:0}") BigDecimal dailyCapUsd) {
        this.dailyCapUsd = dailyCapUsd;
    }

    // Cap 0 = no global cap.
    public synchronized boolean globalBudgetLeft() {
        rollOver();
        return dailyCapUsd.signum() <= 0 || spentToday.compareTo(dailyCapUsd) < 0;
    }

    public synchronized void add(BigDecimal costUsd) {
        rollOver();
        spentToday = spentToday.add(costUsd);
    }

    private void rollOver() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            spentToday = BigDecimal.ZERO;
        }
    }
}
