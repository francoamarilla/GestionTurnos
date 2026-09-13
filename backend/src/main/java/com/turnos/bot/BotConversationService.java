package com.turnos.bot;

import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentService;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.appointments.CreateAppointmentRequest;
import com.turnos.appointments.RescheduleAppointmentRequest;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.customers.CustomerService;
import com.turnos.customers.CreateCustomerRequest;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalServiceAssignment;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.whatsapp.WhatsAppIntegration;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import com.turnos.whatsapp.WhatsAppProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// The deterministic, button/list-driven bot engine (spec section 5 - no
// NLU/LLM). Works purely against InboundMessage, never the raw webhook
// payload, so it doesn't need to know or guess the chosen BSP's shape
// (spec section 13). WhatsAppWebhookController will build an InboundMessage
// from a real payload and call handleMessage() once a BSP is picked.
//
@org.springframework.stereotype.Service
public class BotConversationService {

    private static final Logger log = LoggerFactory.getLogger(BotConversationService.class);

    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String FAILURES_KEY = "failures";
    private static final String SERVICE_ID_KEY = "serviceId";
    private static final String PROFESSIONAL_SELECTION_KEY = "professionalSelection";
    private static final String PROFESSIONAL_ID_KEY = "professionalId";
    private static final String SLOT_START_KEY = "slotStart";
    private static final String MODE_KEY = "mode";
    private static final String MODE_BOOK = "BOOK";
    private static final String MODE_RESCHEDULE = "RESCHEDULE";
    private static final String RESCHEDULE_APPOINTMENT_ID_KEY = "rescheduleAppointmentId";
    private static final String ROOT_MENU_PROMPT = "¡Hola! 👋 ¿Qué querés hacer?";

    private static final String OPTION_BOOK = "BOOK";
    private static final String OPTION_VIEW = "VIEW";
    private static final String OPTION_CANCEL = "CANCEL";
    private static final String OPTION_HUMAN = "HUMAN";
    private static final String OPTION_ANY_PROFESSIONAL = "ANY";
    private static final String OPTION_CONFIRM_BOOKING = "CONFIRM_BOOKING";
    private static final String OPTION_CANCEL_BOOKING = "CANCEL_BOOKING";
    private static final String OPTION_CANCEL_APPOINTMENT = "CANCEL_APPT";
    private static final String OPTION_RESCHEDULE_APPOINTMENT = "RESCHEDULE_APPT";

    // Generous enough that "expand the range" (spec section 5's no-
    // availability handling) rarely needs its own extra round trip; capped
    // to a WhatsApp interactive list's practical row limit.
    private static final int BOOKING_SEARCH_DAYS = 14;
    private static final int MAX_SLOT_OPTIONS = 8;

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;
    private final BotConversationStateRepository botConversationStateRepository;
    private final TenantSessionContext tenantSessionContext;
    private final TenantRepository tenantRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final ServiceRepository serviceRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository;
    private final AppointmentService appointmentService;
    private final BotAppointmentBooker botAppointmentBooker;
    private final com.turnos.availability.AvailabilityService availabilityService;
    private final WhatsAppProvider whatsAppProvider;

    public BotConversationService(
            WhatsAppIntegrationRepository whatsAppIntegrationRepository,
            BotConversationStateRepository botConversationStateRepository,
            TenantSessionContext tenantSessionContext,
            TenantRepository tenantRepository,
            CustomerRepository customerRepository,
            CustomerService customerService,
            ServiceRepository serviceRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository,
            AppointmentService appointmentService,
            BotAppointmentBooker botAppointmentBooker,
            com.turnos.availability.AvailabilityService availabilityService,
            WhatsAppProvider whatsAppProvider) {
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
        this.botConversationStateRepository = botConversationStateRepository;
        this.tenantSessionContext = tenantSessionContext;
        this.tenantRepository = tenantRepository;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.serviceRepository = serviceRepository;
        this.professionalRepository = professionalRepository;
        this.professionalServiceAssignmentRepository = professionalServiceAssignmentRepository;
        this.appointmentService = appointmentService;
        this.botAppointmentBooker = botAppointmentBooker;
        this.availabilityService = availabilityService;
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
            MDC.put("tenantId", tenantId.toString());
            process(tenantId, message.businessPhoneNumber(), message);
            tenantSessionContext.flush();
        } finally {
            tenantSessionContext.reset();
            MDC.remove("tenantId");
        }
    }

    private void process(UUID tenantId, String businessNumber, InboundMessage message) {
        String phone = message.customerPhoneNumber();
        BotConversationState state =
                botConversationStateRepository.findByTenantIdAndCustomerPhone(tenantId, phone).orElse(null);
        boolean expired = state != null && state.getExpiresAt().isBefore(Instant.now());
        boolean freshStart = state == null || expired;

        if (freshStart) {
            state = new BotConversationState();
            state.setTenantId(tenantId);
            state.setCustomerPhone(phone);
            state.setCollectedData(new HashMap<>());
        }
        touchExpiry(state);

        // "Hablar con alguien" is always available, from any step, never
        // just one option buried in whatever list happens to be showing
        // (spec section 5) - checked before anything step-specific, and
        // ahead of even identifying a brand-new number.
        if (state.getCurrentStep() != BotStep.HUMAN_HANDOFF && isHumanRequest(message)) {
            startHumanHandoff(businessNumber, phone, state);
            return;
        }

        if (freshStart) {
            state.setCurrentStep(
                    customerRepository.findByTenantIdAndPhone(tenantId, phone).isEmpty()
                            ? BotStep.AWAITING_NAME
                            : BotStep.ROOT_MENU);
            botConversationStateRepository.save(state);
            if (state.getCurrentStep() == BotStep.AWAITING_NAME) {
                whatsAppProvider.sendText(businessNumber, phone, "¡Hola! 👋 Antes de arrancar, ¿cómo es tu nombre?");
            } else {
                showRootMenu(businessNumber, phone);
            }
            return;
        }

        switch (state.getCurrentStep()) {
            case AWAITING_NAME -> handleNameCapture(tenantId, businessNumber, phone, state, message);
            case ROOT_MENU -> handleRootMenuSelection(tenantId, businessNumber, phone, state, message);
            case BOOKING_SELECT_SERVICE -> handleServiceSelection(tenantId, businessNumber, phone, state, message);
            case BOOKING_SELECT_PROFESSIONAL ->
                handleProfessionalSelection(tenantId, businessNumber, phone, state, message);
            case BOOKING_SELECT_SLOT -> handleSlotSelection(tenantId, businessNumber, phone, state, message);
            case BOOKING_CONFIRM -> handleBookingConfirmation(tenantId, businessNumber, phone, state, message);
            case VIEWING_APPOINTMENTS -> handleAppointmentSelection(tenantId, businessNumber, phone, state, message);
            case APPOINTMENT_ACTION -> handleAppointmentAction(tenantId, businessNumber, phone, state, message);
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
        state.getCollectedData().put(MODE_KEY, MODE_BOOK);
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
        List<WhatsAppProvider.ListRow> rows = new ArrayList<>(services.stream()
                .map(s -> new WhatsAppProvider.ListRow(
                        s.getId().toString(), s.getName(), s.getDurationMinutes() + " min - $" + s.getPrice()))
                .toList());
        rows.add(humanHandoffRow());
        whatsAppProvider.sendList(businessNumber, phone, "¿Qué servicio querés reservar?", "Ver servicios", rows);
    }

    private void handleServiceSelection(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        UUID serviceId = parseUuidOrNull(message.selectionId());
        Service service = serviceId == null ? null : serviceRepository.findById(serviceId).orElse(null);
        if (service == null || !service.isActive() || !service.getTenantId().equals(tenantId)) {
            handleUnmatchedInput(tenantId, businessNumber, phone, state);
            return;
        }
        state.getCollectedData().put(SERVICE_ID_KEY, service.getId().toString());
        state.setCurrentStep(BotStep.BOOKING_SELECT_PROFESSIONAL);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendProfessionalList(businessNumber, phone, service.getId());
    }

    private void sendProfessionalList(String businessNumber, String phone, UUID serviceId) {
        List<Professional> professionals = eligibleProfessionals(serviceId);
        List<WhatsAppProvider.ListRow> rows = new ArrayList<>();
        rows.add(new WhatsAppProvider.ListRow(OPTION_ANY_PROFESSIONAL, "Cualquiera disponible", null));
        for (Professional professional : professionals) {
            rows.add(new WhatsAppProvider.ListRow(professional.getId().toString(), professional.getName(), null));
        }
        rows.add(humanHandoffRow());
        whatsAppProvider.sendList(businessNumber, phone, "¿Con quién preferís el turno?", "Ver profesionales", rows);
    }

    private void handleProfessionalSelection(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String selectionId = message.selectionId();
        UUID serviceId = UUID.fromString(state.getCollectedData().get(SERVICE_ID_KEY));
        String professionalSelection;
        if (OPTION_ANY_PROFESSIONAL.equals(selectionId)) {
            professionalSelection = OPTION_ANY_PROFESSIONAL;
        } else {
            UUID candidate = parseUuidOrNull(selectionId);
            boolean offersService = candidate != null
                    && eligibleProfessionals(serviceId).stream().anyMatch(p -> p.getId().equals(candidate));
            if (!offersService) {
                handleUnmatchedInput(tenantId, businessNumber, phone, state);
                return;
            }
            professionalSelection = candidate.toString();
        }
        state.getCollectedData().put(PROFESSIONAL_SELECTION_KEY, professionalSelection);
        state.setCurrentStep(BotStep.BOOKING_SELECT_SLOT);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendSlotList(tenantId, businessNumber, phone, state);
    }

    private void sendSlotList(UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        UUID serviceId = UUID.fromString(state.getCollectedData().get(SERVICE_ID_KEY));
        String professionalSelection = state.getCollectedData().get(PROFESSIONAL_SELECTION_KEY);
        List<SlotOption> slots = findSlots(tenantId, serviceId, professionalSelection);
        if (slots.isEmpty()) {
            if (!OPTION_ANY_PROFESSIONAL.equals(professionalSelection)) {
                whatsAppProvider.sendButtons(
                        businessNumber,
                        phone,
                        "No encontré horarios disponibles con ese profesional en los próximos días.",
                        List.of(
                                new WhatsAppProvider.Button(OPTION_ANY_PROFESSIONAL, "Probar cualquiera disponible"),
                                humanHandoffButton()));
                return;
            }
            whatsAppProvider.sendText(
                    businessNumber,
                    phone,
                    "No hay horarios disponibles en los próximos días. Te derivo con alguien del local.");
            startHumanHandoff(businessNumber, phone, state);
            return;
        }
        List<WhatsAppProvider.ListRow> rows = new ArrayList<>(
                slots.stream().map(s -> new WhatsAppProvider.ListRow(s.rowId(), s.label(), null)).toList());
        rows.add(humanHandoffRow());
        whatsAppProvider.sendList(
                businessNumber, phone, "Estos son los próximos horarios disponibles:", "Ver horarios", rows);
    }

    private void handleSlotSelection(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String selectionId = message.selectionId();
        if (OPTION_HUMAN.equals(selectionId)) {
            startHumanHandoff(businessNumber, phone, state);
            return;
        }
        if (OPTION_ANY_PROFESSIONAL.equals(selectionId)) {
            state.getCollectedData().put(PROFESSIONAL_SELECTION_KEY, OPTION_ANY_PROFESSIONAL);
            botConversationStateRepository.save(state);
            sendSlotList(tenantId, businessNumber, phone, state);
            return;
        }
        SlotOption chosen = selectionId == null ? null : parseSlotSelection(selectionId);
        if (chosen == null) {
            handleUnmatchedInput(tenantId, businessNumber, phone, state);
            return;
        }
        state.getCollectedData().put(PROFESSIONAL_ID_KEY, chosen.professionalId().toString());
        state.getCollectedData().put(SLOT_START_KEY, chosen.startTime().toString());
        state.setCurrentStep(BotStep.BOOKING_CONFIRM);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendBookingConfirmation(tenantId, businessNumber, phone, state);
    }

    private void sendBookingConfirmation(UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        UUID serviceId = UUID.fromString(state.getCollectedData().get(SERVICE_ID_KEY));
        UUID professionalId = UUID.fromString(state.getCollectedData().get(PROFESSIONAL_ID_KEY));
        OffsetDateTime slotStart = OffsetDateTime.parse(state.getCollectedData().get(SLOT_START_KEY));
        Service service = serviceRepository.findById(serviceId).orElseThrow();
        Professional professional = professionalRepository.findById(professionalId).orElseThrow();

        boolean rescheduling = MODE_RESCHEDULE.equals(state.getCollectedData().get(MODE_KEY));

        StringBuilder text = new StringBuilder();
        if (!rescheduling && hasSameDayConfirmedAppointment(tenantId, phone, slotStart)) {
            text.append("Ojo: ya tenés otro turno confirmado ese mismo día.\n");
        }
        text.append(rescheduling ? "¿Confirmás la reprogramación?\n" : "¿Confirmás este turno?\n")
                .append(service.getName())
                .append(" con ")
                .append(professional.getName())
                .append(" el ")
                .append(slotStart.format(DISPLAY_FORMAT))
                .append(" - $")
                .append(service.getPrice());

        whatsAppProvider.sendButtons(
                businessNumber,
                phone,
                text.toString(),
                List.of(
                        new WhatsAppProvider.Button(OPTION_CONFIRM_BOOKING, "Confirmar"),
                        new WhatsAppProvider.Button(OPTION_CANCEL_BOOKING, "Cancelar"),
                        humanHandoffButton()));
    }

    private void handleBookingConfirmation(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String selectionId = message.selectionId();
        boolean rescheduling = MODE_RESCHEDULE.equals(state.getCollectedData().get(MODE_KEY));
        if (OPTION_CANCEL_BOOKING.equals(selectionId)) {
            state.setCurrentStep(BotStep.ROOT_MENU);
            botConversationStateRepository.save(state);
            whatsAppProvider.sendText(
                    businessNumber, phone, rescheduling ? "Sin problema, dejé tu turno como estaba." : "Sin problema, no reservé nada.");
            showRootMenu(businessNumber, phone);
            return;
        }
        if (!OPTION_CONFIRM_BOOKING.equals(selectionId)) {
            handleUnmatchedInput(tenantId, businessNumber, phone, state);
            return;
        }

        UUID serviceId = UUID.fromString(state.getCollectedData().get(SERVICE_ID_KEY));
        UUID professionalId = UUID.fromString(state.getCollectedData().get(PROFESSIONAL_ID_KEY));
        OffsetDateTime slotStart = OffsetDateTime.parse(state.getCollectedData().get(SLOT_START_KEY));
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, phone).orElse(null);
        UUID customerId = customer == null ? null : customer.getId();

        try {
            if (rescheduling) {
                UUID appointmentId = UUID.fromString(state.getCollectedData().get(RESCHEDULE_APPOINTMENT_ID_KEY));
                botAppointmentBooker.attemptReschedule(
                        tenantId,
                        customerId,
                        appointmentId,
                        new RescheduleAppointmentRequest(slotStart, professionalId, serviceId));
            } else {
                botAppointmentBooker.attemptCreate(
                        tenantId, new CreateAppointmentRequest(professionalId, serviceId, slotStart, customerId));
            }
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                // Someone else took it in the interim (spec section 5, step 5)
                // - never fail silently, just re-offer what's free now.
                whatsAppProvider.sendText(
                        businessNumber, phone, "Justo se ocupó ese horario. Te muestro los horarios actualizados.");
                state.setCurrentStep(BotStep.BOOKING_SELECT_SLOT);
                botConversationStateRepository.save(state);
                sendSlotList(tenantId, businessNumber, phone, state);
                return;
            }
            throw ex;
        }
        state.setCurrentStep(BotStep.ROOT_MENU);
        botConversationStateRepository.save(state);
        whatsAppProvider.sendText(
                businessNumber, phone, rescheduling ? "¡Listo! Tu turno quedó reprogramado. ✅" : "¡Listo! Tu turno quedó confirmado. ✅");
    }

    private record SlotOption(String rowId, String label, UUID professionalId, OffsetDateTime startTime) {}

    private List<SlotOption> findSlots(UUID tenantId, UUID serviceId, String professionalSelection) {
        List<Professional> candidates = OPTION_ANY_PROFESSIONAL.equals(professionalSelection)
                ? eligibleProfessionals(serviceId)
                : professionalRepository
                        .findById(UUID.fromString(professionalSelection))
                        .filter(Professional::isActive)
                        .map(List::of)
                        .orElse(List.of());
        boolean showProfessionalName =
                OPTION_ANY_PROFESSIONAL.equals(professionalSelection) && candidates.size() > 1;

        ZoneId zone = ZoneId.of(
                tenantRepository.findById(tenantId).orElseThrow().getTimezone());
        LocalDate today = LocalDate.now(zone);

        List<SlotOption> options = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < BOOKING_SEARCH_DAYS; dayOffset++) {
            LocalDate date = today.plusDays(dayOffset);
            for (Professional professional : candidates) {
                for (OffsetDateTime slot :
                        availabilityService.getAvailability(tenantId, professional.getId(), serviceId, date)) {
                    String label = slot.format(DISPLAY_FORMAT)
                            + (showProfessionalName ? " - " + professional.getName() : "");
                    options.add(new SlotOption(professional.getId() + "|" + slot, label, professional.getId(), slot));
                }
            }
        }
        options.sort(Comparator.comparing(SlotOption::startTime));
        return options.size() > MAX_SLOT_OPTIONS ? options.subList(0, MAX_SLOT_OPTIONS) : options;
    }

    private SlotOption parseSlotSelection(String selectionId) {
        int separator = selectionId.indexOf('|');
        if (separator < 0) {
            return null;
        }
        try {
            UUID professionalId = UUID.fromString(selectionId.substring(0, separator));
            OffsetDateTime startTime = OffsetDateTime.parse(selectionId.substring(separator + 1));
            return new SlotOption(selectionId, null, professionalId, startTime);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<Professional> eligibleProfessionals(UUID serviceId) {
        List<UUID> professionalIds = professionalServiceAssignmentRepository.findByServiceId(serviceId).stream()
                .map(ProfessionalServiceAssignment::getProfessionalId)
                .toList();
        return professionalRepository.findAllById(professionalIds).stream()
                .filter(Professional::isActive)
                .toList();
    }

    private boolean hasSameDayConfirmedAppointment(UUID tenantId, String phone, OffsetDateTime slotStart) {
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, phone).orElse(null);
        if (customer == null) {
            return false;
        }
        return appointmentService.findByCustomer(tenantId, customer.getId()).stream()
                .anyMatch(a -> a.status() == AppointmentStatus.CONFIRMED
                        && a.startTime() != null
                        && a.startTime().toLocalDate().equals(slotStart.toLocalDate()));
    }

    private void startViewingAppointments(
            UUID tenantId, String businessNumber, String phone, BotConversationState state) {
        state.setCurrentStep(BotStep.VIEWING_APPOINTMENTS);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendAppointmentList(tenantId, businessNumber, phone);
    }

    private List<AppointmentResponse> upcomingConfirmedAppointments(UUID tenantId, String phone) {
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, phone).orElse(null);
        if (customer == null) {
            return List.of();
        }
        return appointmentService.findByCustomer(tenantId, customer.getId()).stream()
                .filter(a -> a.status() == AppointmentStatus.CONFIRMED
                        && a.startTime().toInstant().isAfter(Instant.now()))
                .toList();
    }

    private String describeAppointment(AppointmentResponse appointment) {
        String serviceName =
                serviceRepository.findById(appointment.serviceId()).map(Service::getName).orElse("Servicio");
        String professionalName = professionalRepository
                .findById(appointment.professionalId())
                .map(Professional::getName)
                .orElse("Profesional");
        return serviceName + " con " + professionalName + " el " + appointment.startTime().format(DISPLAY_FORMAT);
    }

    private void sendAppointmentList(UUID tenantId, String businessNumber, String phone) {
        List<AppointmentResponse> upcoming = upcomingConfirmedAppointments(tenantId, phone);
        if (upcoming.isEmpty()) {
            whatsAppProvider.sendText(
                    businessNumber, phone, "No tenés turnos próximos. Escribinos \"reservar\" para sacar uno.");
            return;
        }
        List<WhatsAppProvider.ListRow> rows = new ArrayList<>(upcoming.stream()
                .map(a -> new WhatsAppProvider.ListRow(a.id().toString(), describeAppointment(a), null))
                .toList());
        rows.add(humanHandoffRow());
        whatsAppProvider.sendList(businessNumber, phone, "¿Cuál de tus turnos querés ver?", "Ver turnos", rows);
    }

    private void handleAppointmentSelection(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        UUID appointmentId = parseUuidOrNull(message.selectionId());
        boolean owned = appointmentId != null
                && upcomingConfirmedAppointments(tenantId, phone).stream()
                        .anyMatch(a -> a.id().equals(appointmentId));
        if (!owned) {
            handleUnmatchedInput(tenantId, businessNumber, phone, state);
            return;
        }
        state.getCollectedData().put(RESCHEDULE_APPOINTMENT_ID_KEY, appointmentId.toString());
        state.setCurrentStep(BotStep.APPOINTMENT_ACTION);
        resetFailures(state);
        botConversationStateRepository.save(state);
        sendAppointmentActionPrompt(businessNumber, phone);
    }

    private void sendAppointmentActionPrompt(String businessNumber, String phone) {
        whatsAppProvider.sendButtons(
                businessNumber,
                phone,
                "¿Qué querés hacer con ese turno?",
                List.of(
                        new WhatsAppProvider.Button(OPTION_CANCEL_APPOINTMENT, "Cancelar"),
                        new WhatsAppProvider.Button(OPTION_RESCHEDULE_APPOINTMENT, "Reprogramar"),
                        humanHandoffButton()));
    }

    private void handleAppointmentAction(
            UUID tenantId, String businessNumber, String phone, BotConversationState state, InboundMessage message) {
        String selectionId = message.selectionId();
        UUID appointmentId = UUID.fromString(state.getCollectedData().get(RESCHEDULE_APPOINTMENT_ID_KEY));
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, phone).orElse(null);
        UUID customerId = customer == null ? null : customer.getId();

        if (OPTION_CANCEL_APPOINTMENT.equals(selectionId)) {
            try {
                appointmentService.cancelAsCustomer(tenantId, customerId, appointmentId);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                    // Outside the cancellation window (spec rule 12): never a
                    // flat refusal - explain the policy and the human option
                    // is right there on the root menu we show next.
                    whatsAppProvider.sendText(
                            businessNumber,
                            phone,
                            "Ese turno ya está dentro de la ventana en la que no se puede cancelar o reprogramar solo. "
                                    + "Si necesitás ayuda, elegí \"Hablar con alguien del local\".");
                    state.setCurrentStep(BotStep.ROOT_MENU);
                    botConversationStateRepository.save(state);
                    showRootMenu(businessNumber, phone);
                    return;
                }
                throw ex;
            }
            state.setCurrentStep(BotStep.ROOT_MENU);
            botConversationStateRepository.save(state);
            whatsAppProvider.sendText(businessNumber, phone, "Listo, cancelé ese turno. ✅");
            showRootMenu(businessNumber, phone);
            return;
        }

        if (OPTION_RESCHEDULE_APPOINTMENT.equals(selectionId)) {
            AppointmentResponse original = appointmentService.findById(tenantId, appointmentId);
            state.getCollectedData().put(MODE_KEY, MODE_RESCHEDULE);
            state.getCollectedData().put(SERVICE_ID_KEY, original.serviceId().toString());
            // Same professional by default (spec section 5); changing it is
            // out of scope here - the customer can always fall back to
            // "Hablar con alguien" if they need a different one.
            state.getCollectedData().put(PROFESSIONAL_SELECTION_KEY, original.professionalId().toString());
            state.setCurrentStep(BotStep.BOOKING_SELECT_SLOT);
            resetFailures(state);
            botConversationStateRepository.save(state);
            sendSlotList(tenantId, businessNumber, phone, state);
            return;
        }

        handleUnmatchedInput(tenantId, businessNumber, phone, state);
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
            case BOOKING_SELECT_PROFESSIONAL ->
                sendProfessionalList(
                        businessNumber, phone, UUID.fromString(state.getCollectedData().get(SERVICE_ID_KEY)));
            case BOOKING_SELECT_SLOT -> sendSlotList(tenantId, businessNumber, phone, state);
            case BOOKING_CONFIRM -> sendBookingConfirmation(tenantId, businessNumber, phone, state);
            case VIEWING_APPOINTMENTS -> sendAppointmentList(tenantId, businessNumber, phone);
            case APPOINTMENT_ACTION -> sendAppointmentActionPrompt(businessNumber, phone);
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

    // "Siempre visible, nunca escondida en un submenú" (spec section 5):
    // every list/button prompt the bot sends includes this, on top of the
    // global isHumanRequest() short-circuit in process() that makes tapping
    // or typing it work from any step, not just the ones offering it.
    private WhatsAppProvider.ListRow humanHandoffRow() {
        return new WhatsAppProvider.ListRow(OPTION_HUMAN, "Hablar con alguien del local", null);
    }

    private WhatsAppProvider.Button humanHandoffButton() {
        return new WhatsAppProvider.Button(OPTION_HUMAN, "Hablar con alguien");
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
        // Not OPTION_HUMAN here: isHumanRequest() in process() already
        // intercepts that before this method ever runs.
        return null;
    }

    private boolean isHumanRequest(InboundMessage message) {
        if (OPTION_HUMAN.equalsIgnoreCase(message.selectionId())) {
            return true;
        }
        if (message.text() == null) {
            return false;
        }
        String normalized = message.text().trim().toLowerCase(Locale.ROOT);
        return normalized.contains("hablar") || normalized.contains("persona") || normalized.contains("human")
                || normalized.contains("asesor");
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
