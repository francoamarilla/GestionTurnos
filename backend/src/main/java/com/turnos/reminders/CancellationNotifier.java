package com.turnos.reminders;

import com.turnos.appointments.AppointmentResponse;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.whatsapp.WhatsAppIntegration;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import com.turnos.whatsapp.WhatsAppProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// "Mensaje de cancelación por el negocio como plantilla" (spec section 6):
// unlike the bot's own customer-initiated cancellation - which tells the
// customer inline, as a free-text message within the active chat (spec
// section 5) - a business-initiated cancellation happens outside any chat
// window, so it has to go out as an approved template, same as the
// scheduled reminders. Called from AppointmentController (already running
// inside the request's own tenant context, via the JWT-derived
// TenantContextFilter - no manual apply()/reset() needed here, unlike the
// reminder job/bot engine which have no HTTP request to piggyback on).
@Component
public class CancellationNotifier {

    private final CustomerRepository customerRepository;
    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;
    private final ReminderSentRepository reminderSentRepository;
    private final WhatsAppProvider whatsAppProvider;

    public CancellationNotifier(
            CustomerRepository customerRepository,
            WhatsAppIntegrationRepository whatsAppIntegrationRepository,
            ReminderSentRepository reminderSentRepository,
            WhatsAppProvider whatsAppProvider) {
        this.customerRepository = customerRepository;
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
        this.reminderSentRepository = reminderSentRepository;
        this.whatsAppProvider = whatsAppProvider;
    }

    @Transactional
    public void notifyBusinessCancellation(UUID tenantId, AppointmentResponse appointment) {
        if (appointment.customerId() == null) {
            return; // anonymous walk-in - nothing to notify
        }
        // Same idempotency ledger as the sweep job: a retried PATCH (e.g. a
        // flaky dashboard double-submit) must not notify the customer twice.
        if (reminderSentRepository.existsByAppointmentIdAndTypeAndStatus(
                appointment.id(), ReminderType.CANCELLATION, ReminderStatus.SENT)) {
            return;
        }
        Customer customer = customerRepository.findById(appointment.customerId()).orElse(null);
        if (customer == null || customer.getPhone() == null) {
            return;
        }
        WhatsAppIntegration integration =
                whatsAppIntegrationRepository.findByTenantId(tenantId).orElse(null);
        if (integration == null || integration.getPhoneNumber() == null) {
            return;
        }

        whatsAppProvider.sendTemplate(
                integration.getPhoneNumber(), customer.getPhone(), ReminderType.CANCELLATION.name(), List.of());

        ReminderSent record = new ReminderSent();
        record.setAppointmentId(appointment.id());
        record.setType(ReminderType.CANCELLATION);
        record.setStatus(ReminderStatus.SENT);
        record.setAttempts(1);
        record.setSentAt(Instant.now());
        reminderSentRepository.save(record);
    }
}
