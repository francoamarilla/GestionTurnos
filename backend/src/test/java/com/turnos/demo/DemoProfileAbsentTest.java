package com.turnos.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

// The demo seed is configuration, not a branch of the codebase: the same jar
// serves the public demo and a real deployment, and the difference is only
// SPRING_PROFILES_ACTIVE=demo. This test runs without that profile - the
// default everywhere else - and proves the demo side is genuinely absent
// rather than merely unused.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoProfileAbsentTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @Test
    void demoBeansAreNotLoadedWithoutTheDemoProfile() {
        assertTrue(context.getBeansOfType(DemoDataService.class).isEmpty(), "DemoDataService must not be loaded");
        assertTrue(context.getBeansOfType(DemoDataSeeder.class).isEmpty(), "DemoDataSeeder must not be loaded");
        assertTrue(
                context.getBeansOfType(DemoResetController.class).isEmpty(), "DemoResetController must not be loaded");
    }

    @Test
    void resetEndpointIs404WithoutTheDemoProfile() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/demo/reset"))
                                .header("X-Demo-Reset-Token", "whatever")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        // 404, not 401: SecurityConfig permits the path, so the honest answer
        // is that the route does not exist here.
        assertEquals(404, response.statusCode());
    }
}
