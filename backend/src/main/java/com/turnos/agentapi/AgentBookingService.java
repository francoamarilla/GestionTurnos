package com.turnos.agentapi;

import com.turnos.agentapi.BookingDraftResolver.Named;
import com.turnos.appointments.AppointmentOrigin;
import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentService;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.appointments.CreateAppointmentRequest;
import com.turnos.availability.AvailabilityService;
import com.turnos.conversations.ChatMessageRepository;
import com.turnos.conversations.Conversation;
import com.turnos.conversations.ConversationRepository;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Business logic behind the agent's booking tools. Every method takes the
// conversation from the AGENT token; the customer comes from that
// conversation (set by identifyCustomer), never from the model.
//
// Business refusals come back as a 200 with an "error" field the model can
// read and recover from; only real faults (stale draft, lost race) are 4xx.
@org.springframework.stereotype.Service
public class AgentBookingService {

    static final int MAX_SEARCH_DAYS = 14;
    static final int MAX_SLOTS = 15;
    static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.forLanguageTag("es-AR"));

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalServiceAssignmentRepository assignmentRepository;
    private final ServiceRepository serviceRepository;
    private final CustomerRepository customerRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PendingActionRepository pendingActionRepository;
    private final AvailabilityService availabilityService;
    private final AppointmentService appointmentService;

    public AgentBookingService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalServiceAssignmentRepository assignmentRepository,
            ServiceRepository serviceRepository,
            CustomerRepository customerRepository,
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            PendingActionRepository pendingActionRepository,
            AvailabilityService availabilityService,
            AppointmentService appointmentService) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.professionalRepository = professionalRepository;
        this.assignmentRepository = assignmentRepository;
        this.serviceRepository = serviceRepository;
        this.customerRepository = customerRepository;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.pendingActionRepository = pendingActionRepository;
        this.availabilityService = availabilityService;
        this.appointmentService = appointmentService;
    }

    public record Slot(UUID professionalId, String professional, OffsetDateTime start, String label) {
    }

    public record ProfessionalInfo(UUID id, String name) {
    }

    // ---- reads ------------------------------------------------------------

    public List<ProfessionalInfo> professionals(UUID tenantId, UUID serviceId) {
        return eligibleProfessionals(tenantId, serviceId).stream()
                .map(p -> new ProfessionalInfo(p.getId(), p.getName()))
                .toList();
    }

    public Map<String, Object> searchAvailability(
            UUID tenantId,
            UUID serviceId,
            UUID professionalId,
            LocalDate from,
            LocalDate to,
            LocalTime timeFrom,
            LocalTime timeTo) {
        Tenant tenant = tenant(tenantId);
        if (!tenant.isOnlineBookingEnabled()) {
            return Map.of("error", "online_booking_disabled");
        }
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        LocalDate end = to == null ? start : to;
        LocalDate cap = start.plusDays(MAX_SEARCH_DAYS - 1);
        if (tenant.getBookingHorizonDays() != null) {
            LocalDate horizon = today.plusDays(tenant.getBookingHorizonDays());
            cap = cap.isAfter(horizon) ? horizon : cap;
        }
        end = end.isAfter(cap) ? cap : end;

        List<Professional> professionals = professionalId == null
                ? eligibleProfessionals(tenantId, serviceId)
                : eligibleProfessionals(tenantId, serviceId).stream()
                        .filter(p -> p.getId().equals(professionalId))
                        .toList();
        if (professionals.isEmpty()) {
            return Map.of("error", "no_professional_offers_service");
        }
        List<Slot> slots = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end) && slots.size() < MAX_SLOTS; d = d.plusDays(1)) {
            List<Slot> day = new ArrayList<>();
            for (Professional p : professionals) {
                for (OffsetDateTime s : availabilityService.getBookableAvailability(tenantId, p.getId(), serviceId, d)) {
                    LocalTime t = s.atZoneSameInstant(zone).toLocalTime();
                    if ((timeFrom == null || !t.isBefore(timeFrom)) && (timeTo == null || !t.isAfter(timeTo))) {
                        day.add(slot(p, s, zone));
                    }
                }
            }
            day.sort(Comparator.comparing(Slot::start));
            slots.addAll(day);
        }
        return Map.of(
                "slots", slots.size() > MAX_SLOTS ? slots.subList(0, MAX_SLOTS) : slots,
                "searchedUntil", end.toString());
    }

    // ---- customer -----------------------------------------------------------

    @Transactional
    public Map<String, Object> identifyCustomer(UUID tenantId, UUID conversationId, String name, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return Map.of("error", "invalid_phone");
        }
        String normalized = (phone.strip().startsWith("+") ? "+" : "") + digits;
        Conversation conversation = conversation(conversationId);
        if (conversation.isPlayground()) {
            return Map.of("name", name == null ? "" : name, "known", false, "simulated", true);
        }
        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, normalized).orElse(null);
        boolean known = customer != null;
        if (customer == null) {
            customer = new Customer();
            customer.setTenantId(tenantId);
            customer.setPhone(normalized);
            customer.setName(name == null || name.isBlank() ? "Cliente web" : name.strip());
            customer.setActive(true);
            customer = customerRepository.save(customer);
        } else if (!customer.isActive()) {
            return Map.of("error", "customer_inactive");
        }
        conversation.setCustomerId(customer.getId());
        conversation.setCustomerPhone(normalized);
        conversationRepository.save(conversation);
        return Map.of("name", customer.getName(), "known", known);
    }

    // ---- booking ------------------------------------------------------------

    @Transactional
    public Map<String, Object> prepareBooking(
            UUID tenantId,
            UUID conversationId,
            String serviceText,
            String professionalText,
            String dateText,
            String timeText,
            String confirmationMode) {
        Tenant tenant = tenant(tenantId);
        if (!tenant.isOnlineBookingEnabled()) {
            return Map.of("error", "online_booking_disabled");
        }
        Conversation conversation = conversation(conversationId);
        if (conversation.getCustomerId() == null && !conversation.isPlayground()) {
            return Map.of("error", "customer_not_identified", "hint", "Pedí nombre y teléfono y llamá a identify_customer.");
        }
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);

        List<Named> services = serviceRepository.findByTenantId(tenantId).stream()
                .filter(Service::isActive)
                .map(s -> new Named(s.getId(), s.getName()))
                .toList();
        var r = BookingDraftResolver.resolve(
                serviceText,
                professionalText,
                dateText,
                timeText,
                services,
                serviceId -> eligibleProfessionals(tenantId, serviceId).stream()
                        .map(p -> new Named(p.getId(), p.getName()))
                        .toList(),
                now.toLocalDate());
        if (!r.concrete()) {
            return Map.of("error", "needs_clarification", "blockers", r.blockers(), "candidates", r.candidates());
        }
        if (tenant.getBookingHorizonDays() != null
                && r.date().isAfter(now.toLocalDate().plusDays(tenant.getBookingHorizonDays()))) {
            return Map.of("error", "beyond_booking_horizon", "maxDays", tenant.getBookingHorizonDays());
        }
        OffsetDateTime start = r.date().atTime(r.time()).atZone(zone).toOffsetDateTime();
        if (!start.toInstant().isAfter(now.toInstant())) {
            return Map.of("error", "time_in_past");
        }

        // The customer already holds this exact slot (e.g. an extra "sí" after
        // the commit): say so, instead of a slot_unavailable that reads as
        // "not booked". The chat history has no tool results to tell the model.
        if (conversation.getCustomerId() != null) {
            var existing = appointmentService.findByCustomer(tenantId, conversation.getCustomerId()).stream()
                    .filter(a -> a.status() == AppointmentStatus.CONFIRMED && a.startTime().isEqual(start))
                    .findFirst();
            if (existing.isPresent()) {
                Service booked = serviceRepository.findById(existing.get().serviceId()).orElse(null);
                return Map.of(
                        "error", "already_booked",
                        "appointmentId", existing.get().id(),
                        "summary", (booked == null ? "Turno" : booked.getName()) + " con "
                                + professionalName(existing.get().professionalId()) + ", "
                                + start.atZoneSameInstant(zone).format(LABEL));
            }
        }

        // The requested time must be free as asked, otherwise offer others.
        List<Professional> candidates = eligibleProfessionals(tenantId, r.serviceId()).stream()
                .filter(p -> r.anyProfessional() || p.getId().equals(r.professionalId()))
                .toList();
        Professional chosen = candidates.stream()
                .filter(p -> availabilityService.getBookableAvailability(tenantId, p.getId(), r.serviceId(), r.date())
                        .stream()
                        .anyMatch(s -> s.isEqual(start)))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return Map.of("error", "slot_unavailable", "alternatives", alternatives(tenantId, candidates, r.serviceId(), r.date(), zone));
        }

        List<String> ambiguities = new ArrayList<>(r.ambiguities());
        boolean sameDayBooking = conversation.getCustomerId() != null
                && appointmentService.findByCustomer(tenantId, conversation.getCustomerId()).stream()
                .anyMatch(a -> a.status() == AppointmentStatus.CONFIRMED
                        && a.startTime().atZoneSameInstant(zone).toLocalDate().equals(r.date()));
        if (sameDayBooking) {
            ambiguities.add("possible_duplicate");
        }

        Service service = serviceRepository.findById(r.serviceId()).orElseThrow();
        PendingAction draft = new PendingAction();
        draft.setTenantId(tenantId);
        draft.setConversationId(conversationId);
        draft.setType("BOOKING");
        draft.setProfessionalId(chosen.getId());
        draft.setServiceId(service.getId());
        draft.setStartTime(start.toInstant());
        draft.setSummary(service.getName() + " con " + chosen.getName() + ", " + start.atZoneSameInstant(zone).format(LABEL)
                + ", $" + price(service.getPrice()));
        draft.setAmbiguities(ambiguities);
        draft.setMode(BookingDraftResolver.mode(confirmationMode, ambiguities));
        pendingActionRepository.save(draft);
        return Map.of(
                "draftId", draft.getId(),
                "summary", draft.getSummary(),
                "mode", draft.getMode(),
                "ambiguities", ambiguities);
    }

    @Transactional
    public Map<String, Object> commitBooking(UUID tenantId, UUID conversationId, UUID draftId) {
        PendingAction draft = openDraft(conversationId, draftId, "BOOKING");
        Conversation conversation = conversation(conversationId);
        if (conversation.isPlayground()) {
            draft.setStatus("COMMITTED");
            pendingActionRepository.save(draft);
            return Map.of("simulated", true, "summary", draft.getSummary());
        }
        AppointmentResponse created = appointmentService.create(
                tenantId,
                new CreateAppointmentRequest(
                        draft.getProfessionalId(),
                        draft.getServiceId(),
                        draft.getStartTime().atZone(ZoneId.of(tenant(tenantId).getTimezone())).toOffsetDateTime(),
                        conversation.getCustomerId()),
                AppointmentOrigin.BOT);
        draft.setStatus("COMMITTED");
        draft.setAppointmentId(created.id());
        pendingActionRepository.save(draft);
        return Map.of("appointmentId", created.id(), "summary", draft.getSummary());
    }

    // ---- the customer's own appointments -----------------------------------

    public record MyAppointment(UUID id, String service, String professional, OffsetDateTime start, String label) {
    }

    public Map<String, Object> myAppointments(UUID tenantId, UUID conversationId) {
        UUID customerId = conversation(conversationId).getCustomerId();
        if (customerId == null) {
            return Map.of("error", "customer_not_identified", "hint", "Pedí nombre y teléfono y llamá a identify_customer.");
        }
        ZoneId zone = ZoneId.of(tenant(tenantId).getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zone);
        List<MyAppointment> upcoming = appointmentService.findByCustomer(tenantId, customerId).stream()
                .filter(a -> a.status() == AppointmentStatus.CONFIRMED && a.startTime().isAfter(now))
                .sorted(Comparator.comparing(AppointmentResponse::startTime))
                .map(a -> new MyAppointment(
                        a.id(),
                        serviceName(a.serviceId()),
                        professionalName(a.professionalId()),
                        a.startTime(),
                        a.startTime().atZoneSameInstant(zone).format(LABEL)))
                .toList();
        return Map.of("appointments", upcoming);
    }

    // "Memory" of the customer comes from the database, not from the model.
    public Map<String, Object> customerProfile(UUID tenantId, UUID conversationId) {
        UUID customerId = conversation(conversationId).getCustomerId();
        if (customerId == null) {
            return Map.of("error", "customer_not_identified");
        }
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        List<AppointmentResponse> history = appointmentService.findByCustomer(tenantId, customerId).stream()
                .filter(a -> a.status() == AppointmentStatus.COMPLETED || a.status() == AppointmentStatus.CONFIRMED)
                .filter(a -> a.serviceId() != null)
                .sorted(Comparator.comparing(AppointmentResponse::startTime).reversed())
                .toList();
        String usualProfessional = history.stream()
                .collect(Collectors.groupingBy(AppointmentResponse::professionalId, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> professionalName(e.getKey()))
                .orElse(null);
        var profile = new java.util.HashMap<String, Object>();
        profile.put("name", customer.getName());
        profile.put("visits", history.size());
        profile.put("lastService", history.isEmpty() ? null : serviceName(history.get(0).serviceId()));
        profile.put("usualProfessional", usualProfessional);
        return profile;
    }

    // Cancelling always asks first (decision A2): a wrong cancellation is
    // only noticed when the customer shows up and has no appointment.
    @Transactional
    public Map<String, Object> prepareCancellation(UUID tenantId, UUID conversationId, UUID appointmentId) {
        Conversation conversation = conversation(conversationId);
        Tenant tenant = tenant(tenantId);
        AppointmentResponse appointment = ownedConfirmedAppointment(tenantId, conversation, appointmentId);
        if (appointment == null) {
            return Map.of("error", "appointment_not_found");
        }
        if (!withinCancellationWindow(tenant, appointment)) {
            return Map.of("error", "outside_cancellation_window", "hours", tenant.getCancellationWindowHours());
        }
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        PendingAction draft = new PendingAction();
        draft.setTenantId(tenantId);
        draft.setConversationId(conversationId);
        draft.setType("CANCELLATION");
        draft.setAppointmentId(appointmentId);
        draft.setSummary("Cancelar " + serviceName(appointment.serviceId()) + " con "
                + professionalName(appointment.professionalId()) + ", "
                + appointment.startTime().atZoneSameInstant(zone).format(LABEL));
        draft.setMode("CONFIRM");
        pendingActionRepository.save(draft);
        return Map.of("draftId", draft.getId(), "summary", draft.getSummary(), "mode", "CONFIRM");
    }

    @Transactional
    public Map<String, Object> commitCancellation(UUID tenantId, UUID conversationId, UUID draftId) {
        PendingAction draft = openDraft(conversationId, draftId, "CANCELLATION");
        Conversation conversation = conversation(conversationId);
        if (!conversation.isPlayground()) {
            appointmentService.cancelAsCustomer(tenantId, conversation.getCustomerId(), draft.getAppointmentId());
        }
        draft.setStatus("COMMITTED");
        pendingActionRepository.save(draft);
        return Map.of("cancelled", true, "summary", draft.getSummary());
    }

    @Transactional
    public Map<String, Object> prepareReschedule(
            UUID tenantId,
            UUID conversationId,
            UUID appointmentId,
            String professionalText,
            String dateText,
            String timeText,
            String confirmationMode) {
        Conversation conversation = conversation(conversationId);
        Tenant tenant = tenant(tenantId);
        AppointmentResponse appointment = ownedConfirmedAppointment(tenantId, conversation, appointmentId);
        if (appointment == null) {
            return Map.of("error", "appointment_not_found");
        }
        if (!withinCancellationWindow(tenant, appointment)) {
            return Map.of("error", "outside_cancellation_window", "hours", tenant.getCancellationWindowHours());
        }
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        String service = serviceName(appointment.serviceId());
        // Same service; same professional unless the customer names another.
        String professional = professionalText == null || professionalText.isBlank()
                ? professionalName(appointment.professionalId())
                : professionalText;
        var r = BookingDraftResolver.resolve(
                service,
                professional,
                dateText,
                timeText,
                List.of(new Named(appointment.serviceId(), service)),
                serviceId -> eligibleProfessionals(tenantId, serviceId).stream()
                        .map(p -> new Named(p.getId(), p.getName()))
                        .toList(),
                now.toLocalDate());
        if (!r.concrete()) {
            return Map.of("error", "needs_clarification", "blockers", r.blockers(), "candidates", r.candidates());
        }
        OffsetDateTime start = r.date().atTime(r.time()).atZone(zone).toOffsetDateTime();
        if (!start.toInstant().isAfter(now.toInstant())) {
            return Map.of("error", "time_in_past");
        }
        List<Professional> candidates = eligibleProfessionals(tenantId, appointment.serviceId()).stream()
                .filter(p -> r.anyProfessional() || p.getId().equals(r.professionalId()))
                .toList();
        Professional chosen = candidates.stream()
                .filter(p -> availabilityService
                        .getBookableAvailability(tenantId, p.getId(), appointment.serviceId(), r.date())
                        .stream()
                        .anyMatch(s -> s.isEqual(start)))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return Map.of(
                    "error", "slot_unavailable",
                    "alternatives", alternatives(tenantId, candidates, appointment.serviceId(), r.date(), zone));
        }
        PendingAction draft = new PendingAction();
        draft.setTenantId(tenantId);
        draft.setConversationId(conversationId);
        draft.setType("RESCHEDULE");
        draft.setAppointmentId(appointmentId);
        draft.setProfessionalId(chosen.getId());
        draft.setServiceId(appointment.serviceId());
        draft.setStartTime(start.toInstant());
        draft.setSummary("Pasar " + service + " del " + appointment.startTime().atZoneSameInstant(zone).format(LABEL)
                + " al " + start.atZoneSameInstant(zone).format(LABEL) + " con " + chosen.getName());
        draft.setAmbiguities(r.ambiguities());
        draft.setMode(BookingDraftResolver.mode(confirmationMode, r.ambiguities()));
        pendingActionRepository.save(draft);
        return Map.of(
                "draftId", draft.getId(), "summary", draft.getSummary(), "mode", draft.getMode(),
                "ambiguities", r.ambiguities());
    }

    @Transactional
    public Map<String, Object> commitReschedule(UUID tenantId, UUID conversationId, UUID draftId) {
        PendingAction draft = openDraft(conversationId, draftId, "RESCHEDULE");
        Conversation conversation = conversation(conversationId);
        if (conversation.isPlayground()) {
            draft.setStatus("COMMITTED");
            pendingActionRepository.save(draft);
            return Map.of("simulated", true, "summary", draft.getSummary());
        }
        ZoneId zone = ZoneId.of(tenant(tenantId).getTimezone());
        AppointmentResponse replacement = appointmentService.rescheduleAsCustomer(
                tenantId,
                conversation.getCustomerId(),
                draft.getAppointmentId(),
                new com.turnos.appointments.RescheduleAppointmentRequest(
                        draft.getStartTime().atZone(zone).toOffsetDateTime(), draft.getProfessionalId(), null));
        draft.setStatus("COMMITTED");
        pendingActionRepository.save(draft);
        return Map.of("appointmentId", replacement.id(), "summary", draft.getSummary());
    }

    private AppointmentResponse ownedConfirmedAppointment(UUID tenantId, Conversation conversation, UUID appointmentId) {
        if (conversation.getCustomerId() == null || appointmentId == null) {
            return null;
        }
        return appointmentService.findByCustomer(tenantId, conversation.getCustomerId()).stream()
                .filter(a -> a.id().equals(appointmentId) && a.status() == AppointmentStatus.CONFIRMED)
                .findFirst()
                .orElse(null);
    }

    private static boolean withinCancellationWindow(Tenant tenant, AppointmentResponse a) {
        int hours = tenant.getCancellationWindowHours() == null ? 0 : tenant.getCancellationWindowHours();
        return java.time.Instant.now().isBefore(a.startTime().toInstant().minusSeconds(hours * 3600L));
    }

    private String serviceName(UUID serviceId) {
        return serviceId == null ? "Turno" : serviceRepository.findById(serviceId).map(Service::getName).orElse("Turno");
    }

    private String professionalName(UUID professionalId) {
        return professionalId == null
                ? "-"
                : professionalRepository.findById(professionalId).map(Professional::getName).orElse("-");
    }

    // What the agent should know at the start of a turn: the open draft, if any.
    public PendingAction openDraftFor(UUID conversationId) {
        return pendingActionRepository
                .findFirstByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, "PENDING")
                .filter(PendingAction::isOpen)
                .orElse(null);
    }

    // A CONFIRM draft needs a customer message written after it: the yes.
    // The model can't fake that - it only writes assistant messages.
    PendingAction openDraft(UUID conversationId, UUID draftId, String type) {
        PendingAction draft = pendingActionRepository
                .findById(draftId)
                .filter(d -> d.getConversationId().equals(conversationId) && type.equals(d.getType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
        if (!draft.isOpen()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Draft expired or already used: prepare it again");
        }
        if ("CONFIRM".equals(draft.getMode())
                && !chatMessageRepository.existsByConversationIdAndRoleAndCreatedAtAfter(
                        conversationId, "USER", draft.getCreatedAt())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This draft needs the customer's confirmation first: show the summary and ask");
        }
        return draft;
    }

    // ---- helpers ------------------------------------------------------------

    List<Professional> eligibleProfessionals(UUID tenantId, UUID serviceId) {
        var branch = branchRepository.findByTenantId(tenantId).orElse(null);
        if (branch == null) {
            return List.of();
        }
        Set<UUID> offering = assignmentRepository.findByServiceId(serviceId).stream()
                .map(a -> a.getProfessionalId())
                .collect(Collectors.toSet());
        return professionalRepository.findByBranchIdAndActive(branch.getId(), true, Pageable.unpaged()).stream()
                .filter(p -> offering.contains(p.getId()))
                .sorted(Comparator.comparing(Professional::getName))
                .toList();
    }

    private List<String> alternatives(
            UUID tenantId, List<Professional> professionals, UUID serviceId, LocalDate date, ZoneId zone) {
        List<Slot> found = new ArrayList<>();
        for (LocalDate d = date; found.size() < 3 && d.isBefore(date.plusDays(7)); d = d.plusDays(1)) {
            List<Slot> day = new ArrayList<>();
            for (Professional p : professionals) {
                availabilityService.getBookableAvailability(tenantId, p.getId(), serviceId, d)
                        .forEach(s -> day.add(slot(p, s, zone)));
            }
            day.sort(Comparator.comparing(Slot::start));
            found.addAll(day);
        }
        return found.stream().limit(3).map(s -> s.label() + " con " + s.professional()).toList();
    }

    private static Slot slot(Professional p, OffsetDateTime s, ZoneId zone) {
        return new Slot(p.getId(), p.getName(), s, s.atZoneSameInstant(zone).format(LABEL));
    }

    private static String price(BigDecimal price) {
        return price == null ? "-" : price.stripTrailingZeros().toPlainString();
    }

    Tenant tenant(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Conversation conversation(UUID conversationId) {
        return conversationRepository
                .findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }
}
