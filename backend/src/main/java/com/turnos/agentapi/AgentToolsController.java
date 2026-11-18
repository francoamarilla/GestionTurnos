package com.turnos.agentapi;

import com.turnos.auth.AuthenticatedPrincipal;
import com.turnos.conversations.ConversationService;
import com.turnos.establishment.DaySchedule;
import com.turnos.establishment.EstablishmentService;
import com.turnos.services.ServiceCatalogService;
import com.turnos.services.ServiceResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The AI agent's tools (propuesta-arquitectura-ia.md, section 5). Only an
// AGENT token gets here (SecurityConfig). The tenant always comes from that
// token - TenantContextFilter has already applied it for RLS - and the
// conversation is the token's subject; neither is ever something the model
// wrote. Responses are trimmed to what the model needs.
@RestController
@RequestMapping("/internal/agent")
public class AgentToolsController {

    private final EstablishmentService establishmentService;
    private final ServiceCatalogService serviceCatalogService;
    private final AgentBookingService bookingService;
    private final AgentPolicy agentPolicy;
    private final ConversationService conversationService;

    public AgentToolsController(
            EstablishmentService establishmentService,
            ServiceCatalogService serviceCatalogService,
            AgentBookingService bookingService,
            AgentPolicy agentPolicy,
            ConversationService conversationService) {
        this.establishmentService = establishmentService;
        this.serviceCatalogService = serviceCatalogService;
        this.bookingService = bookingService;
        this.agentPolicy = agentPolicy;
        this.conversationService = conversationService;
    }

    @GetMapping("/business-info")
    public BusinessInfo businessInfo(@AuthenticationPrincipal AuthenticatedPrincipal agent) {
        var e = establishmentService.get(agent.tenantId());
        return new BusinessInfo(
                e.businessName(),
                e.address(),
                e.contactPhone(),
                e.timezone(),
                e.cancellationWindowHours(),
                e.onlineBookingEnabled(),
                e.schedule());
    }

    @GetMapping("/services")
    public List<AgentService> services(@AuthenticationPrincipal AuthenticatedPrincipal agent) {
        return serviceCatalogService.findAll(agent.tenantId()).stream()
                .filter(ServiceResponse::active)
                .map(s -> new AgentService(s.id(), s.name(), s.description(), s.durationMinutes(), s.price()))
                .toList();
    }

    @GetMapping("/professionals")
    public List<AgentBookingService.ProfessionalInfo> professionals(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @RequestParam UUID serviceId) {
        return bookingService.professionals(agent.tenantId(), serviceId);
    }

    @PostMapping("/availability/search")
    public Map<String, Object> searchAvailability(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody AvailabilitySearch body) {
        return bookingService.searchAvailability(
                agent.tenantId(),
                body.serviceId(),
                body.professionalId(),
                body.dateFrom(),
                body.dateTo(),
                body.timeFrom(),
                body.timeTo());
    }

    @PostMapping("/customer/identify")
    public Map<String, Object> identifyCustomer(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody IdentifyCustomer body) {
        return bookingService.identifyCustomer(agent.tenantId(), agent.id(), body.name(), body.phone());
    }

    @PostMapping("/bookings/prepare")
    public Map<String, Object> prepareBooking(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody PrepareBooking body) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.BOOK);
        return bookingService.prepareBooking(
                agent.tenantId(),
                agent.id(),
                body.serviceText(),
                body.professionalText(),
                body.dateText(),
                body.timeText(),
                agentPolicy.confirmationMode(agent.tenantId(), agent.id()));
    }

    @PostMapping("/bookings/{draftId}/commit")
    public Map<String, Object> commitBooking(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @PathVariable UUID draftId) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.BOOK);
        return bookingService.commitBooking(agent.tenantId(), agent.id(), draftId);
    }

    @GetMapping("/customer/appointments")
    public Map<String, Object> myAppointments(@AuthenticationPrincipal AuthenticatedPrincipal agent) {
        return bookingService.myAppointments(agent.tenantId(), agent.id());
    }

    @GetMapping("/customer/profile")
    public Map<String, Object> customerProfile(@AuthenticationPrincipal AuthenticatedPrincipal agent) {
        return bookingService.customerProfile(agent.tenantId(), agent.id());
    }

    @PostMapping("/cancellations/prepare")
    public Map<String, Object> prepareCancellation(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody AppointmentRef body) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.CANCEL);
        return bookingService.prepareCancellation(agent.tenantId(), agent.id(), body.appointmentId());
    }

    @PostMapping("/cancellations/{draftId}/commit")
    public Map<String, Object> commitCancellation(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @PathVariable UUID draftId) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.CANCEL);
        return bookingService.commitCancellation(agent.tenantId(), agent.id(), draftId);
    }

    @PostMapping("/reschedules/prepare")
    public Map<String, Object> prepareReschedule(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody PrepareReschedule body) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.RESCHEDULE);
        return bookingService.prepareReschedule(
                agent.tenantId(),
                agent.id(),
                body.appointmentId(),
                body.professionalText(),
                body.dateText(),
                body.timeText(),
                agentPolicy.confirmationMode(agent.tenantId(), agent.id()));
    }

    @PostMapping("/reschedules/{draftId}/commit")
    public Map<String, Object> commitReschedule(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @PathVariable UUID draftId) {
        agentPolicy.require(agent.tenantId(), agent.id(), AgentPolicy.Capability.RESCHEDULE);
        return bookingService.commitReschedule(agent.tenantId(), agent.id(), draftId);
    }

    // Pass the conversation to a person: the AI stops answering it and the
    // owner sees it under "Derivadas" in the Asistente screen.
    @PostMapping("/handoff")
    public Map<String, Object> handoff(
            @AuthenticationPrincipal AuthenticatedPrincipal agent, @Valid @RequestBody Handoff body) {
        var conversation = bookingService.conversation(agent.id());
        conversationService.markHandoff(conversation, body.reason());
        return Map.of("handedOff", true);
    }

    public record Handoff(@Size(max = 500) String reason) {
    }

    public record AppointmentRef(@NotNull UUID appointmentId) {
    }

    public record PrepareReschedule(
            @NotNull UUID appointmentId,
            @Size(max = 100) String professionalText,
            @NotNull @Size(max = 100) String dateText,
            @NotNull @Size(max = 100) String timeText) {
    }

    public record BusinessInfo(
            String name,
            String address,
            String phone,
            String timezone,
            Integer cancellationWindowHours,
            boolean onlineBookingEnabled,
            List<DaySchedule> weeklySchedule) {
    }

    public record AgentService(UUID id, String name, String description, Integer durationMinutes, BigDecimal price) {
    }

    public record AvailabilitySearch(
            @NotNull UUID serviceId,
            UUID professionalId,
            LocalDate dateFrom,
            LocalDate dateTo,
            LocalTime timeFrom,
            LocalTime timeTo) {
    }

    public record IdentifyCustomer(@Size(max = 100) String name, @NotNull @Size(max = 30) String phone) {
    }

    public record PrepareBooking(
            @NotNull @Size(max = 100) String serviceText,
            @Size(max = 100) String professionalText,
            @NotNull @Size(max = 100) String dateText,
            @NotNull @Size(max = 100) String timeText) {
    }
}
