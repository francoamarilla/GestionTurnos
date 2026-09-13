package com.turnos.reminders;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

// "Job de recordatorios corriendo" (spec section 10): reports DOWN if the
// sweep hasn't completed a pass recently enough to still be considered
// alive, rather than just checking the process is up (that much is already
// covered by Actuator's own liveness).
@Component("reminderJob")
public class ReminderJobHealthIndicator implements HealthIndicator {

    // Tolerates one slow/missed cycle before calling it unhealthy.
    private static final int MISSED_CYCLES_TOLERATED = 3;

    private final AtomicReference<Instant> lastCompletedAt = new AtomicReference<>();
    private final Duration staleAfter;

    public ReminderJobHealthIndicator(@Value("${app.reminders.sweep-interval-ms:600000}") long sweepIntervalMs) {
        this.staleAfter = Duration.ofMillis(sweepIntervalMs * MISSED_CYCLES_TOLERATED);
    }

    void recordCompletedRun() {
        lastCompletedAt.set(Instant.now());
    }

    @Override
    public Health health() {
        Instant last = lastCompletedAt.get();
        if (last == null) {
            // Hasn't had a chance to run yet (app just started) - not a
            // failure in itself.
            return Health.up().withDetail("lastRunAt", "never").build();
        }
        Duration sinceLastRun = Duration.between(last, Instant.now());
        if (sinceLastRun.compareTo(staleAfter) > 0) {
            return Health.down()
                    .withDetail("lastRunAt", last)
                    .withDetail("staleFor", sinceLastRun)
                    .build();
        }
        return Health.up().withDetail("lastRunAt", last).build();
    }
}
