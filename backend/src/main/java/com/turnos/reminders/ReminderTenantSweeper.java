package com.turnos.reminders;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Split out of ReminderSweepJob (its only caller) so @Transactional
// actually takes effect: sweep() calling a method on this - a different
// bean - goes through the real Spring proxy, unlike calling another
// @Transactional method on itself (self-invocation silently skips the
// proxy, same reasoning documented on AppointmentService.createInternal).
@Component
class ReminderTenantSweeper {

    private final TenantSessionContext tenantSessionContext;
    private final BranchRepository branchRepository;
    private final AppointmentRepository appointmentRepository;
    private final CustomerRepository customerRepository;
    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;
    private final ReminderSentRepository reminderSentRepository;
    private final WhatsAppProvider whatsAppProvider;

    ReminderTenantSweeper(
            TenantSessionContext tenantSessionContext,
            BranchRepository branchRepository,
            AppointmentRepository appointmentRepository,
            CustomerRepository customerRepository,
            WhatsAppIntegrationRepository whatsAppIntegrationRepository,
            ReminderSentRepository reminderSentRepository,
            WhatsAppProvider whatsAppProvider) {
        this.tenantSessionContext = tenantSessionContext;
        this.branchRepository = branchRepository;
        this.appointmentRepository = appointmentRepository;
        this.customerRepository = customerRepository;
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
        this.reminderSentRepository = reminderSentRepository;
        this.whatsAppProvider = whatsAppProvider;
    }

    @Transactional
    void sweepTenant(UUID tenantId, List<ReminderRule> activeRules) {
        try {
            tenantSessionContext.apply(false, tenantId);
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
        if (reminderSentRepository.existsByAppointmentIdAndTypeAndStatus(
                appointment.getId(), type, ReminderStatus.SENT)) {
            return;
        }
        // Anonymous walk-in or a dashboard-created customer with no phone on
        // file (spec section 4, rule 19) - nothing to notify, and no amount
        // of retrying would ever change that.
        Customer customer = appointment.getCustomerId() == null
                ? null
                : customerRepository.findById(appointment.getCustomerId()).orElse(null);
        if (customer == null || customer.getPhone() == null) {
            return;
        }

        // Reminder templateName is an internal identifier, not a real Meta-
        // approved template name - the exact BSP-side naming is pending the
        // provider choice (spec section 13), same reasoning as the rest of
        // com.turnos.whatsapp. Rule 24: reminders always go out as a
        // template, never a free-text message.
        whatsAppProvider.sendTemplate(businessPhoneNumber, customer.getPhone(), type.name(), List.of());

        ReminderSent record = new ReminderSent();
        record.setAppointmentId(appointment.getId());
        record.setType(type);
        record.setStatus(ReminderStatus.SENT);
        record.setAttempts(1);
        record.setSentAt(Instant.now());
        reminderSentRepository.save(record);
    }
}
