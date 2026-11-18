package com.turnos.conversations;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

// Cloudflare Turnstile (captcha) for the public demo, checked once per new
// chat session. No secret configured = disabled (local dev, tests).
@Component
public class TurnstileVerifier {

    private final String secret;
    private final RestClient restClient = RestClient.create("https://challenges.cloudflare.com");

    public TurnstileVerifier(@Value("${app.turnstile.secret:}") String secret) {
        this.secret = secret;
    }

    public boolean enabled() {
        return !secret.isBlank();
    }

    public boolean verify(String token, String remoteIp) {
        if (!enabled()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null) {
            form.add("remoteip", remoteIp);
        }
        try {
            Map<?, ?> result = restClient.post()
                    .uri("/turnstile/v0/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
