package com.turnos.professionals;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/professionals")
public class ProfessionalController {

    private final ProfessionalService professionalService;

    public ProfessionalController(ProfessionalService professionalService) {
        this.professionalService = professionalService;
    }

    @GetMapping
    public Page<ProfessionalResponse> findAll(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) Boolean active,
            Pageable pageable) {
        return professionalService.findAll(principal.tenantId(), active, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfessionalResponse create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CreateProfessionalRequest request) {
        return professionalService.create(principal.tenantId(), request);
    }

    @GetMapping("/{id}")
    public ProfessionalResponse findById(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return professionalService.findById(principal.tenantId(), id);
    }

    @PatchMapping("/{id}")
    public ProfessionalResponse update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProfessionalRequest request) {
        return professionalService.update(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        professionalService.deactivate(principal.tenantId(), id);
    }

    @GetMapping("/{id}/schedule")
    public List<DaySchedule> getSchedule(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return professionalService.getSchedule(principal.tenantId(), id);
    }

    @PatchMapping("/{id}/schedule")
    public List<DaySchedule> updateSchedule(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @RequestBody List<DaySchedule> schedule) {
        return professionalService.updateSchedule(principal.tenantId(), id, schedule);
    }
}
