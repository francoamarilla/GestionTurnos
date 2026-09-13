package com.turnos.appointments;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Manual alta from the dashboard; the bot creates appointments in-process,
// never through this HTTP endpoint (spec section 11).
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public List<AppointmentResponse> search(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID professionalId,
            @RequestParam(required = false) AppointmentStatus status) {
        return appointmentService.search(principal.tenantId(), from, to, professionalId, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CreateAppointmentRequest request) {
        return appointmentService.create(principal.tenantId(), request);
    }

    @GetMapping("/{id}")
    public AppointmentResponse findById(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return appointmentService.findById(principal.tenantId(), id);
    }

    @PatchMapping("/{id}/status")
    public AppointmentResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAppointmentStatusRequest request) {
        return appointmentService.updateStatus(principal.tenantId(), principal.id(), id, request.status());
    }
}
