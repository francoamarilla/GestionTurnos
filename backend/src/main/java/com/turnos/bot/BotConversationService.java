package com.turnos.bot;

import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentService;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.customers.CustomerService;
import com.turnos.customers.CreateCustomerRequest;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.whatsapp.WhatsAppIntegration;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import com.turnos.whatsapp.WhatsAppProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

// The deterministic, button/list-driven bot engine (spec section 5 - no
// NLU/LLM). Works purely against InboundMessage, never the raw webhook
// payload, so it doesn't need to know or guess the chosen BSP's shape
// (spec section 13). WhatsAppWebhookController will build an InboundMessage
// from a real payload and call handleMessage() once a BSP is picked.
//
// BOOKING_SELECT_SERVICE and VIEWING_APPOINTMENTS currently only *show*
// their step's options (service list / upcoming appointments); matching a
// specific selection within those steps lands in the next commits (spec
// section 5's booking and view/cancel/reschedule flows) - until then, any
// reply while in those steps is handled as unmatched input, same as
// anything unrecognized at the root menu.
@org.springframework.stereotype.Service
public class BotConversationService {

    private static final Logger log = LoggerFactory.getLogger(BotConversationService.class);

    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String FAILURES_KEY = "failures";
    private static final String ROOT_MENU_PROMPT = "¡Hola! 👋 ¿Qué querés hacer?";

    private static final String OPTION_BOOK = "BOOK";
    private static final String OPTION_VIEW = "VIEW";
    private static final String OPTION_CANCEL = "CANCEL";
    private static final String OPTION_HUMAN = "HUMAN";

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;
    private final BotConversationStateRepository botConversationStateRepository;
    private final TenantSessionContext tenantSessionContext;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final ServiceRepository serviceRepository;
    private final ProfessionalRepository professionalRepository;
    private final AppointmentService appointmentService;
    private final WhatsAppProvider whatsAppProvider;

    public BotConversationService(
            WhatsAppIntegrationRepository whatsAppIntegrationRepository,
            BotConversationStateRepository botConversationStateRepository,
            TenantSessionContext tenantSessionContext,
            CustomerRepository customerRepository,
            CustomerService customerService,
            ServiceRepository serviceRepository,
            ProfessionalRepository professionalRepository,
            AppointmentService appointmentService,
            WhatsAppProvider whatsAppProvider) {
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
        this.botConversationStateRepository = botConversationStateRepository;
        this.tenantSessionContext = tenantSessionContext;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.serviceRepository = serviceRepository;
        this.professionalRepository = professionalRepository;
        this.appointmentService = appointmentService;
        this.whatsAppProvider = whatsAppProvider;
    }

    // @Transactional (not just repository-per-call) so the whole exchange
    // runs against one DB connection/session - there's no HTTP request here
    // to give us open-in-view's single-connection-per-request behavior,
    // which is what TenantContextFilter normally relies on.
    @Transactional
    public void handleMessage(InboundMessage message) {
        WhatsAppIntegration integration = whatsAppIntegrationRepository
                .findByPhoneNumber(message.businessPhoneNumber())
                .orElse(null);
        if (integration == null) {
            log.warn("No tenant configured for WhatsApp business number {}", message.businessPhoneNumber());
            return;
        }
        UUID tenantId = integration.getTenantId();
        try {
            tenantSessionContext.apply(false, tenantId);
            process(tenantId, message.businessPhoneNumber(), message);
            tenantSessionContext.flush();
        } finally {
            tenantSessionContext.reset();
        }
    }

    private void process(UUID tenantId, String businessNumber, InboundMessage message) {
        String phone = message.customerPhoneNumber();
        BotConversationState state =
                botConversationStateRepository.findByTenantIdAndCustomerPhone(tenantId, phone).orElse(null);
        boolean expired = state != null && state.getExpiresAt().isBefore(Instant.now());

        if (state == null || expired) {
            state = new BotConversationState();
            state.setTenantId(tenantId);
            state.setCustomerPhone(phone);
            state.setCollectedData(new HashMap<>());
            state.setCurrentStep(
                    customerRepository.findByTenantIdAndPhone(tenantId, phone).isEmpty()
                            ? BotStep.AWAITING_NAME
                            : BotStep.ROOT_MENU);
            touchExpiry(state);
            botConversationStateRepository.save(state);
            if (state.getCurrentStep() == BotStep.AWAITING_NAME) {
                whatsAppProvider.sendText(businessNumber, phone, "¡Hola! 👋 Antes de arrancar, ¿cómo es tu nombre?");
            } else {
                showRootMenu(businessNumber, phone);
            }
            return;
        }

        touchExpiry(state);
        switch (state.getCurrentStep()) {
            case AWAITING_NAME -> handleNameCapture(tenantId, businessNumber, phone, state, message);
            case ROOT_MENU -> handleRootMenuSelection(tenantId, businessNumber, phone, state, message);
            case HUMAN_HANDOFF -> botConversationStateRepository.save(state);
            default -> handleUnmatchedInput(tenantId, businessNumber, phone, state);
        }
    }

    private void handleNameCapture(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String name = message.text() == null ? "" : message.text().trim();
        if (name.isEmpty()) {
            botConversationStateRepository.save(state);
            whatsAppProvider.sendText(businessNumber, phone, "No entendí tu nombre, ¿podés escribirlo de nuevo?");
            return;
        }
        customerService.create(tenantId, new CreateCustomerRequest(phone, name, null));
        state.setCurrentStep(BotStep.ROOT_MENU);
        botConversationStateRepository.save(state);
        showRootMenu(businessNumber, phone);
    }

    private void handleRootMenuSelection(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String selection = resolveSelection(message);
        if (selection == null) {
            handleUnmatchedInput(tenantId, businessNumber, phone, state);
            return;
        }
        switch (selection) {
            case OPTION_BOOK -> startBooking(tenantId, businessNumber, phone, state);
            case OPTION_VIEW, OPTION_CANCEL -> startViewingAppointments(tenantId, businessNumber, phone, state);
            case OPTION_HUMAN -> startHumanHandoff(businessNumber, phone, state);
            default -> handleUnmatchedInput(tenantId, businessNumber, phone, state);
        }
    }

    private void startBooking(UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        state.setCurrentStep(BotStep.BOOKING_SELECT_SERVICE);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendServiceList(tenantId, businessNumber, phone);
    }

    private void sendServiceList(UUID tenantId, String businessNumber, String phone) {
        List<Service> services =
                serviceRepository.findByTenantId(tenantId).stream().filter(Service::isActive).toList();
        if (services.isEmpty()) {
            whatsAppProvider.sendText(
                    businessNumber, phone, "Por ahora no tenemos servicios disponibles para reservar.");
            return;
        }
        List<WhatsAppProvider.ListRow> rows = services.stream()
                .map(s -> new WhatsAppProvider.ListRow(
                        s.getId().toString(), s.getName(), s.getDurationMinutes() + " min - $" + s.getPrice()))
                .toList();
        whatsAppProvider.sendList(businessNumber, phone, "¿Qué servicio querés reservar?", "Ver servicios", rows);
    }

    private void startViewingAppointments(
            UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        state.setCurrentStep(BotStep.VIEWING_APPOINTMENTS);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendAppointmentList(tenantId, businessNumber, phone);
    }

    private void sendAppointmentList(UUID tenantId, String businessNumber, String phone) {
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, phone).orElse(null);
        List<AppointmentResponse> upcoming = customer == null
                ? List.of()
                : appointmentService.findByCustomer(tenantId, customer.getId()).stream()
                        .filter(a -> a.status() == AppointmentStatus.CONFIRMED
                                && a.startTime().toInstant().isAfter(Instant.now()))
                        .toList();
        if (upcoming.isEmpty()) {
            whatsAppProvider.sendText(
                    businessNumber, phone, "No tenés turnos próximos. Escribinos \"reservar\" para sacar uno.");
            return;
        }
        StringBuilder text = new StringBuilder("Estos son tus próximos turnos:\n");
        for (AppointmentResponse appointment : upcoming) {
            String serviceName = serviceRepository
                    .findById(appointment.serviceId())
                    .map(Service::getName)
                    .orElse("Servicio");
            String professionalName = professionalRepository
                    .findById(appointment.professionalId())
                    .map(Professional::getName)
                    .orElse("Profesional");
            text.append("- ")
                    .append(serviceName)
                    .append(" con ")
                    .append(professionalName)
                    .append(" el ")
                    .append(appointment.startTime().format(DISPLAY_FORMAT))
                    .append('\n');
        }
        whatsAppProvider.sendText(businessNumber, phone, text.toString());
    }

    private void startHumanHandoff(String businessNumber, String phone, BotConversationState state) {
        state.setCurrentStep(BotStep.HUMAN_HANDOFF);
        botConversationStateRepository.save(state);
        whatsAppProvider.sendText(businessNumber, phone, "Listo, en breve te va a responder alguien del local. 🙋");
    }

    private void handleUnmatchedInput(UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        int failures = failureCount(state) + 1;
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            startHumanHandoff(businessNumber, phone, state);
            return;
        }
        state.getCollectedData().put(FAILURES_KEY, String.valueOf(failures));
        botConversationStateRepository.save(state);
        whatsAppProvider.sendText(businessNumber, phone, "No entendí esa opción. 🤔");
        resendStepOptions(tenantId, businessNumber, phone, state);
    }

    private void resendStepOptions(UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        switch (state.getCurrentStep()) {
            case BOOKING_SELECT_SERVICE -> sendServiceList(tenantId, businessNumber, phone);
            case VIEWING_APPOINTMENTS -> sendAppointmentList(tenantId, businessNumber, phone);
            default -> showRootMenu(businessNumber, phone);
        }
    }

    private void showRootMenu(String businessNumber, String phone) {
        List<WhatsAppProvider.ListRow> rows = List.of(
                new WhatsAppProvider.ListRow(OPTION_BOOK, "Reservar un turno", null),
                new WhatsAppProvider.ListRow(OPTION_VIEW, "Ver mis turnos", null),
                new WhatsAppProvider.ListRow(OPTION_CANCEL, "Cancelar o modificar un turno", null),
                new WhatsAppProvider.ListRow(OPTION_HUMAN, "Hablar con alguien del local", null));
        whatsAppProvider.sendList(businessNumber, phone, ROOT_MENU_PROMPT, "Ver opciones", rows);
    }

    // Interactive replies carry the tapped option's id directly; free-typed
    // text (spec section 5's "unexpected text" handling covers everything
    // that doesn't match) gets a small deterministic keyword match against
    // the root menu's own labels - not NLU, just a safety net for when a
    // customer types instead of tapping.
    private String resolveSelection(InboundMessage message) {
        if (message.selectionId() != null) {
            return message.selectionId().trim().toUpperCase(Locale.ROOT);
        }
        if (message.text() == null) {
            return null;
        }
        String normalized = message.text().trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("reserv")) {
            return OPTION_BOOK;
        }
        if (normalized.contains("cancel") || normalized.contains("modific") || normalized.contains("reprogram")) {
            return OPTION_CANCEL;
        }
        if (normalized.contains("turno") || normalized.contains("ver mis")) {
            return OPTION_VIEW;
        }
        if (normalized.contains("hablar") || normalized.contains("persona") || normalized.contains("human")) {
            return OPTION_HUMAN;
        }
        return null;
    }

    private void touchExpiry(BotConversationState state) {
        state.setExpiresAt(Instant.now().plus(SESSION_TIMEOUT));
    }

    private void resetFailures(BotConversationState state) {
        state.getCollectedData().remove(FAILURES_KEY);
    }

    private int failureCount(BotConversationState state) {
        String raw = state.getCollectedData().get(FAILURES_KEY);
        return raw == null ? 0 : Integer.parseInt(raw);
    }
}
