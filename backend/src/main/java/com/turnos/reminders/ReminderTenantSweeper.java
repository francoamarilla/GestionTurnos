package com.turnos.reminders;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.bot.BotConversationStateRepository;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.whatsapp.WhatsAppIntegration;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import com.turnos.whatsapp.WhatsAppProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Split out of ReminderSweepJob (its only caller) so @Transactional
// actually takes effect: sweep() calling a method on this - a different
// bean - goes through the real Spring proxy, unlike calling another
// @Transactional method on itself (self-invocation silently skips the
// proxy, same reasoning documented on AppointmentService.createInternal).
@Component
class ReminderTenantSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReminderTenantSweeper.class);

    private final TenantSessionContext tenantSessionContext;
    private final BranchRepository branchRepository;
    private final AppointmentRepository appointmentRepository;
    private final CustomerRepository customerRepository;
    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;
    private final ReminderSentRepository reminderSentRepository;
    private final BotConversationStateRepository botConversationStateRepository;
    private final WhatsAppProvider whatsAppProvider;

    ReminderTenantSweeper(
            TenantSessionContext tenantSessionContext,
            BranchRepository branchRepository,
            AppointmentRepository appointmentRepository,
            CustomerRepository customerRepository,
            WhatsAppIntegrationRepository whatsAppIntegrationRepository,
            ReminderSentRepository reminderSentRepository,
            BotConversationStateRepository botConversationStateRepository,
            WhatsAppProvider whatsAppProvider) {
        this.tenantSessionContext = tenantSessionContext;
        this.branchRepository = branchRepository;
        this.appointmentRepository = appointmentRepository;
        this.customerRepository = customerRepository;
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
        this.reminderSentRepository = reminderSentRepository;
        this.botConversationStateRepository = botConversationStateRepository;
        this.whatsAppProvider = whatsAppProvider;
    }

    @Transactional
    void sweepTenant(UUID tenantId, List<ReminderRule> activeRules) {
        try {
            tenantSessionContext.apply(false, tenantId);
            MDC.put("tenantId", tenantId.toString());
            Branch branch = branchRepository.findByTenantId(tenantId).orElse(null);
            if (branch == null) {
                return;
            }
            WhatsAppIntegration integration =
                    whatsAppIntegrationRepository.findByTenantId(tenantId).orElse(null);
            if (integration == null || integration.getPhoneNumber() == null) {
                return;
            }
            for (ReminderRule rule : activeRules) {
                processRule(branch.getId(), integration.getPhoneNumber(), rule);
            }
            tenantSessionContext.flush();
        } finally {
            tenantSessionContext.reset();
            MDC.remove("tenantId");
        }
    }

    // Same job/scheduler cleans up expired bot conversations (spec section
    // 6) - superadmin context since this spans every tenant in one pass,
    // same as ReminderSweepJob.listAllTenantIds().
    @Transactional
    void cleanupExpiredConversations() {
        try {
            tenantSessionContext.apply(true, null);
            long deleted = botConversationStateRepository.deleteByExpiresAtBefore(Instant.now());
            tenantSessionContext.flush();
            if (deleted > 0) {
                log.info("Cleaned up {} expired bot conversation(s)", deleted);
            }
        } finally {
            tenantSessionContext.reset();
        }
    }

    private void processRule(UUID branchId, String businessPhoneNumber, ReminderRule rule) {
        Instant now = Instant.now();
        Instant windowEnd = now.plus(rule.offsetHours(), ChronoUnit.HOURS);
        List<Appointment> candidates =
                appointmentRepository.search(branchId, now, windowEnd, null, AppointmentStatus.CONFIRMED);
        for (Appointment appointment : candidates) {
            sendReminderIfNeeded(appointment, rule.type(), businessPhoneNumber);
        }
    }

    private void sendReminderIfNeeded(Appointment appointment, ReminderType type, String businessPhoneNumber) {
        // Anonymous walk-in or a dashboard-created customer with no phone on
        // file (spec section 4, rule 19) - nothing to notify, and no amount
        // of retrying would ever change that.
        Customer customer = appointment.getCustomerId() == null
                ? null
                : customerRepository.findById(appointment.getCustomerId()).orElse(null);
        if (customer == null || customer.getPhone() == null) {
            return;
        }

        ReminderSent record = reminderSentRepository
                .findByAppointmentIdAndType(appointment.getId(), type)
                .orElse(null);
        if (record != null) {
            if (record.getStatus() == ReminderStatus.SENT) {
                return;
            }
            if (record.getAttempts() >= ReminderRetryPolicy.MAX_ATTEMPTS) {
                return; // exhausted - terminal FAILED, visible on the dashboard
            }
            if (Instant.now()
                    .isBefore(record.getCreatedAt().plus(ReminderRetryPolicy.backoffDelay(record.getAttempts())))) {
                return; // still backing off before the next retry
            }
        } else {
            record = new ReminderSent();
            record.setAppointmentId(appointment.getId());
            record.setType(type);
            record.setAttempts(0);
            record.setStatus(ReminderStatus.FAILED);
        }

        try {
            // Reminder templateName is an internal identifier, not a real
            // Meta-approved template name - the exact BSP-side naming is
            // pending the provider choice (spec section 13), same reasoning
            // as the rest of com.turnos.whatsapp. Rule 24: reminders always
            // go out as a template, never a free-text message.
            whatsAppProvider.sendTemplate(businessPhoneNumber, customer.getPhone(), type.name(), List.of());
            record.setStatus(ReminderStatus.SENT);
            record.setSentAt(Instant.now());
        } catch (RuntimeException ex) {
            log.warn(
                    "Reminder send failed for appointment {} ({}), attempt {}",
                    appointment.getId(),
                    type,
                    record.getAttempts() + 1,
                    ex);
            // status stays FAILED - terminal only once attempts hits MAX_ATTEMPTS
        }
        record.setAttempts(record.getAttempts() + 1);
        reminderSentRepository.save(record);
    }
}
