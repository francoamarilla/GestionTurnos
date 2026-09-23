package com.turnos.reminders;

import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantSessionContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Periodic sweep, not one job per appointment (spec section 6): each run
// asks "which CONFIRMED appointments just entered a reminder window and
// haven't gotten that reminder yet" and sends to whatever it finds. A
// cancelled/rescheduled appointment just stops matching the query - no
// explicit cancellation needed. The actual per-tenant work lives in
// ReminderTenantSweeper (needs its own @Transactional proxy boundary, see
// there for why).
@Component
public class ReminderSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderSweepJob.class);

    // Spec section 6's stated defaults; see ReminderRule for why this isn't
    // a per-tenant setting in the MVP.
    private static final List<ReminderRule> ACTIVE_RULES =
            List.of(new ReminderRule(ReminderType.REMINDER_24H, 24), new ReminderRule(ReminderType.REMINDER_2H, 2));

    private final TenantSessionContext tenantSessionContext;
    private final TenantRepository tenantRepository;
    private final ReminderTenantSweeper reminderTenantSweeper;
    private final ReminderJobHealthIndicator reminderJobHealthIndicator;

    public ReminderSweepJob(
            TenantSessionContext tenantSessionContext,
            TenantRepository tenantRepository,
            ReminderTenantSweeper reminderTenantSweeper,
            ReminderJobHealthIndicator reminderJobHealthIndicator) {
        this.tenantSessionContext = tenantSessionContext;
        this.tenantRepository = tenantRepository;
        this.reminderTenantSweeper = reminderTenantSweeper;
        this.reminderJobHealthIndicator = reminderJobHealthIndicator;
    }

    // fixedDelay (not fixedRate): never overlaps itself if a run takes
    // longer than the interval, so there's no need to worry about two
    // sweeps racing each other over the same appointment.
    @Scheduled(fixedDelayString = "${app.reminders.sweep-interval-ms:600000}")
    public void sweep() {
        // A tenant with reminders off (#6) is skipped entirely: the switch
        // covers both the 24 h and the 2 h notice. Confirmations and
        // cancellations aren't reminders and don't go through here.
        for (UUID tenantId : listTenantIdsWithRemindersOn()) {
            try {
                reminderTenantSweeper.sweepTenant(tenantId, ACTIVE_RULES);
            } catch (RuntimeException ex) {
                // One tenant's bad data/config must never stop the rest from
                // getting their reminders (never fails in silence overall,
                // just for the affected tenant - logged loudly instead).
                log.error("Reminder sweep failed for tenant {}", tenantId, ex);
            }
        }
        try {
            reminderTenantSweeper.cleanupExpiredConversations();
        } catch (RuntimeException ex) {
            log.error("Bot conversation cleanup failed", ex);
        }
        // Recorded even if some individual tenant failed above - this job's
        // health is about the scheduler still cycling, not any one tenant's
        // business outcome (that's what GET /api/reminders/failed is for).
        reminderJobHealthIndicator.recordCompletedRun();
    }

    // No flush() before reset() here, unlike ReminderTenantSweeper/
    // BotConversationService: this only reads, there's nothing pending to
    // flush, and flush() itself requires an active transaction - which a
    // plain read through this non-@Transactional method never opens.
    private List<UUID> listTenantIdsWithRemindersOn() {
        try {
            tenantSessionContext.apply(true, null);
            return tenantRepository.findAll().stream()
                    .filter(Tenant::isRemindersEnabled)
                    .map(Tenant::getId)
                    .toList();
        } finally {
            tenantSessionContext.reset();
        }
    }
}
