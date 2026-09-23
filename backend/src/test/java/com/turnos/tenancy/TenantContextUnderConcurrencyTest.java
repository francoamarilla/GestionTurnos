package com.turnos.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

// The RLS GUCs TenantContextFilter sets must reach every query of the same
// request, however many requests run at once. They didn't while open-in-view
// was an interceptor: the filter set them on a connection that went back to
// the pool, and a parallel request could reset it - the tenant then looked
// missing ("Tenant not found") or its rows empty. See
// TenantSessionBindingConfig.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantContextUnderConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;
    private static final int REQUESTS_PER_THREAD = 12;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private String token;

    @BeforeEach
    void setUpTenantAndLogIn() throws Exception {
        String email = "concurrency-" + UUID.randomUUID() + "@test";
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "SELECT set_config('app.is_super_admin', 'true', false)");
            UUID tenantId = insert(
                    connection,
                    "INSERT INTO tenant (business_name, contact_phone, status)"
                            + " VALUES ('Concurrency Test', '+540000000000', 'ACTIVE') RETURNING id");
            execute(connection, "INSERT INTO branch (tenant_id) VALUES ('" + tenantId + "')");
            execute(
                    connection,
                    "INSERT INTO admin_user (tenant_id, email, password_hash) VALUES ('" + tenantId + "', '" + email
                            + "', '" + passwordEncoder.encode("concurrency-pw") + "')");
            execute(
                    connection,
                    "INSERT INTO service (tenant_id, name, duration_minutes, price, active)"
                            + " VALUES ('" + tenantId + "', 'Corte', 30, 5000, true)");
        }

        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/auth/login")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"concurrency-pw\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());

        String rest = login.body().substring(login.body().indexOf("\"accessToken\":\"") + 15);
        token = rest.substring(0, rest.indexOf('"'));
    }

    @Test
    void everyConcurrentRequestSeesItsOwnTenant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<String>> calls = java.util.Collections.nCopies(THREADS, (Callable<String>) () -> {
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    // 404 here is the symptom: the tenant row hidden by RLS.
                    HttpResponse<String> establishment = get("/api/establishment");
                    if (establishment.statusCode() != 200) {
                        return "GET /api/establishment -> " + establishment.statusCode() + " " + establishment.body();
                    }
                    // A quieter symptom: the request survives but its rows
                    // are filtered away.
                    HttpResponse<String> services = get("/api/services");
                    if (services.statusCode() != 200 || !services.body().contains("Corte")) {
                        return "GET /api/services -> " + services.statusCode() + " " + services.body();
                    }
                }
                return null;
            });

            for (Future<String> result : pool.invokeAll(calls)) {
                String failure = result.get();
                assertTrue(failure == null, "a concurrent request lost its tenant context: " + failure);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static UUID insert(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject("id");
        }
    }
}
