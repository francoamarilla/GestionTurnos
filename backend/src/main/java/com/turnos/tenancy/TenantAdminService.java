package com.turnos.tenancy;

import com.turnos.auth.AdminRole;
import com.turnos.auth.AdminUser;
import com.turnos.auth.AdminUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantAdminService {

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantAdminService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<TenantResponse> findAll() {
        return tenantRepository.findAll().stream().map(TenantResponse::from).toList();
    }

    public TenantResponse findById(UUID id) {
        return TenantResponse.from(getOrThrow(id));
    }

    // Creates the tenant, its single MVP branch, and the first Admin login
    // together (spec section 2: alta manual, sin autoregistro público).
    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        if (adminUserRepository.findByEmail(request.adminEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        Tenant tenant = new Tenant();
        tenant.setBusinessName(request.businessName());
        tenant.setContactPhone(request.contactPhone());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setBusinessCategory("barbershop");
        tenant.setCancellationWindowHours(2);
        tenant.setTimezone("America/Argentina/Buenos_Aires");
        // saveAndFlush (not save): @CreationTimestamp is only applied at flush
        // time, and the response built below needs createdAt populated now,
        // not whenever this transaction happens to commit.
        tenant = tenantRepository.saveAndFlush(tenant);

        Branch branch = new Branch();
        branch.setTenantId(tenant.getId());
        branchRepository.save(branch);

        AdminUser admin = new AdminUser();
        admin.setTenantId(tenant.getId());
        admin.setEmail(request.adminEmail());
        admin.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
        admin.setRole(AdminRole.ADMIN);
        admin.setActive(true);
        adminUserRepository.save(admin);

        return TenantResponse.from(tenant);
    }

    @Transactional
    public TenantResponse update(UUID id, UpdateTenantRequest request) {
        Tenant tenant = getOrThrow(id);
        if (request.businessName() != null) {
            tenant.setBusinessName(request.businessName());
        }
        if (request.contactPhone() != null) {
            tenant.setContactPhone(request.contactPhone());
        }
        if (request.status() != null) {
            tenant.setStatus(request.status());
        }
        if (request.cancellationWindowHours() != null) {
            tenant.setCancellationWindowHours(request.cancellationWindowHours());
        }
        return TenantResponse.from(tenantRepository.save(tenant));
    }

    private Tenant getOrThrow(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }
}
