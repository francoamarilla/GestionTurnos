package com.turnos;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Base class for tests needing a real Postgres. @DynamicPropertySource is
// only picked up on the test class itself or a superclass (not on an
// unrelated @Import-ed class), so it lives here rather than alongside the
// container definition in TestcontainersConfiguration.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestcontainersConfiguration.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "turnos_app");
        registry.add("spring.datasource.password", () -> "turnos_app_pw");
        // app.jwt.secret has no default (see application.properties), so the
        // context needs one here. Signs nothing outside these tests.
        registry.add("app.jwt.secret", () -> "dGVzdC1vbmx5LWtleS1ub3QtdXNlZC1hbnl3aGVyZS1lbHNl");
    }
}
