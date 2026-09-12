package com.turnos;

import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Not using @ServiceConnection here: it would wire tests to connect as the
// container's bootstrap POSTGRES_USER, which is always a superuser and
// therefore always bypasses Row-Level Security - see
// postgres-init/init-app-role.sql for why the app (and these tests) must
// run as a separate, ordinary turnos_app role instead. The init script
// (test-init.sql) creates that role; AbstractIntegrationTest's
// @DynamicPropertySource points the datasource at it.
//
// @DynamicPropertySource methods are only discovered on the test class
// itself and its superclasses, not on unrelated @Import-ed classes - hence
// this class only owns the container, and AbstractIntegrationTest (which
// tests extend) is where the property registration actually lives.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withInitScript("test-init.sql");

    static {
        POSTGRES.start();
    }
}
