package com.turnos.services;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceCatalogService serviceCatalogService;

    public ServiceController(ServiceCatalogService serviceCatalogService) {
        this.serviceCatalogService = serviceCatalogService;
    }

    @GetMapping
    public List<ServiceResponse> findAll(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return serviceCatalogService.findAll(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @Valid @RequestBody CreateServiceRequest request) {
        return serviceCatalogService.create(principal.tenantId(), request);
    }

    @GetMapping("/{id}")
    public ServiceResponse findById(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return serviceCatalogService.findById(principal.tenantId(), id);
    }

    @PatchMapping("/{id}")
    public ServiceResponse update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceRequest request) {
        return serviceCatalogService.update(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        serviceCatalogService.deactivate(principal.tenantId(), id);
    }
}
