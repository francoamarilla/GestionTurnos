package com.turnos.reminders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ReminderJobHealthIndicatorTest {

    @Test
    void upBeforeFirstRunAndAfterARecentOne() {
        ReminderJobHealthIndicator indicator = new ReminderJobHealthIndicator(1000L);
        assertEquals(Status.UP, indicator.health().getStatus(), "never having run yet isn't a failure");

        indicator.recordCompletedRun();
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void downOnceStaleForTooLong() throws InterruptedException {
        // 1ms interval * 3 missed-cycle tolerance = 3ms before it's stale.
        ReminderJobHealthIndicator indicator = new ReminderJobHealthIndicator(1L);
        indicator.recordCompletedRun();
        Thread.sleep(50);
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
