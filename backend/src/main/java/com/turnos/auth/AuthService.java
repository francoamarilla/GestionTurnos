package com.turnos.auth;

import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    static final String ROLE_ADMIN = "ADMIN";

    private final SuperAdminRepository superAdminRepository;
    private final AdminUserRepository adminUserRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            SuperAdminRepository superAdminRepository,
            AdminUserRepository adminUserRepository,
            TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.superAdminRepository = superAdminRepository;
        this.adminUserRepository = adminUserRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        var superAdmin = superAdminRepository.findByEmail(request.email());
        if (superAdmin.isPresent() && passwordEncoder.matches(request.password(), superAdmin.get().getPasswordHash())) {
            String token = jwtService.generateToken(
                    superAdmin.get().getId().toString(),
                    Map.of("role", ROLE_SUPER_ADMIN));
            return new LoginResponse(token, jwtService.getExpirationSeconds(), ROLE_SUPER_ADMIN, null, null);
        }

        var adminUser = adminUserRepository.findByEmail(request.email());
        if (adminUser.isPresent()
                && adminUser.get().isActive()
                && passwordEncoder.matches(request.password(), adminUser.get().getPasswordHash())) {
            AdminUser admin = adminUser.get();
            Tenant tenant = tenantRepository.findById(admin.getTenantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
            String token = jwtService.generateToken(
                    admin.getId().toString(),
                    Map.of("role", ROLE_ADMIN, "tenantId", admin.getTenantId().toString()));
            return new LoginResponse(
                    token, jwtService.getExpirationSeconds(), ROLE_ADMIN, admin.getTenantId(), tenant.getBusinessName());
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}
