package com.turnos.tenancy;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Super Admin only - enforced by SecurityConfig's /api/admin/** rule, not
// re-checked here.
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantAdminService tenantAdminService;

    public TenantAdminController(TenantAdminService tenantAdminService) {
        this.tenantAdminService = tenantAdminService;
    }

    @GetMapping
    public List<TenantResponse> findAll() {
        return tenantAdminService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest request) {
        return tenantAdminService.create(request);
    }

    @GetMapping("/{id}")
    public TenantResponse findById(@PathVariable UUID id) {
        return tenantAdminService.findById(id);
    }

    @PatchMapping("/{id}")
    public TenantResponse update(@PathVariable UUID id, @RequestBody UpdateTenantRequest request) {
        return tenantAdminService.update(id, request);
    }
}
