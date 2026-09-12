package com.turnos.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Seeds the one SuperAdmin account (Franco) from environment variables so no
// plaintext or fixed password hash ever lives in a migration file. No-ops if
// the env vars are unset or an account with that email already exists.
@Component
public class SuperAdminSeeder implements CommandLineRunner {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedEmail;
    private final String seedPassword;

    public SuperAdminSeeder(
            SuperAdminRepository superAdminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.superadmin.email:}") String seedEmail,
            @Value("${app.superadmin.password:}") String seedPassword) {
        this.superAdminRepository = superAdminRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEmail = seedEmail;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(String... args) {
        if (seedEmail.isBlank() || seedPassword.isBlank()) {
            return;
        }
        if (superAdminRepository.findByEmail(seedEmail).isPresent()) {
            return;
        }
        SuperAdmin superAdmin = new SuperAdmin();
        superAdmin.setEmail(seedEmail);
        superAdmin.setPasswordHash(passwordEncoder.encode(seedPassword));
        superAdminRepository.save(superAdmin);
    }
}
