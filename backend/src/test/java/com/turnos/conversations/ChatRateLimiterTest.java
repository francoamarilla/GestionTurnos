package com.turnos.conversations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChatRateLimiterTest {

    @Test
    void blocksAfterTheLimitPerKeyOnly() {
        ChatRateLimiter limiter = new ChatRateLimiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.check("ip:1");
        }
        assertThrows(ResponseStatusException.class, () -> limiter.check("ip:1"));
        assertDoesNotThrow(() -> limiter.check("ip:2"));
    }

    @Test
    void globalDailyCapStopsTheAi() {
        AiSpendGuard guard = new AiSpendGuard(new java.math.BigDecimal("0.05"));
        guard.add(new java.math.BigDecimal("0.049"));
        org.junit.jupiter.api.Assertions.assertTrue(guard.globalBudgetLeft());
        guard.add(new java.math.BigDecimal("0.002"));
        org.junit.jupiter.api.Assertions.assertFalse(guard.globalBudgetLeft());
        org.junit.jupiter.api.Assertions.assertTrue(new AiSpendGuard(java.math.BigDecimal.ZERO).globalBudgetLeft());
    }
}
