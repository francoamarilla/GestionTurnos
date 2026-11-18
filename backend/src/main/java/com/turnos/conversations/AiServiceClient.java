package com.turnos.conversations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// Talks to ai-service (Python). Unset base URL = AI is off and every chat
// message goes to the button-bot fallback, so nothing changes for
// deployments that don't run the AI service.
@Component
public class AiServiceClient {

    private final RestClient restClient;

    public AiServiceClient(
            @Value("${app.ai.base-url:}") String baseUrl,
            @Value("${app.ai.service-token:}") String serviceToken,
            @Value("${app.ai.timeout-seconds:45}") int timeoutSeconds) {
        if (baseUrl.isBlank()) {
            this.restClient = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + serviceToken)
                .build();
    }

    public boolean enabled() {
        return restClient != null;
    }

    public TurnResponse turn(TurnRequest request) {
        return restClient.post().uri("/v1/agent/turn").body(request).retrieve().body(TurnResponse.class);
    }

    public record HistoryItem(String role, String content) {
    }

    // The owner's published (or, in the playground, draft) configuration.
    public record AgentConfig(
            int version,
            String assistantName,
            String tone,
            boolean voseo,
            String emojiLevel,
            String greeting,
            String customInstructions,
            String policies,
            String handoffInstructions,
            boolean canBook,
            boolean canCancel,
            boolean canReschedule,
            boolean playground) {
    }

    // The draft waiting for the customer's yes, so "sí" in the next turn
    // can commit it without the model having to remember an id.
    public record PendingInfo(UUID draftId, String type, String summary, String mode) {
    }

    // coreToken: the short-lived AGENT JWT the AI service sends back on every
    // /internal/agent/* call. It carries tenant and conversation; the model
    // never sees or chooses those ids.
    public record TurnRequest(
            String message,
            List<HistoryItem> history,
            String coreToken,
            String businessName,
            String timezone,
            AgentConfig config,
            PendingInfo pendingAction) {
    }

    public record Usage(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens, BigDecimal costUsd) {
    }

    // type: booking_created | booking_cancelled | booking_rescheduled | handoff
    public record Action(String type, UUID appointmentId, String summary) {
    }

    public record TurnResponse(
            String reply,
            Usage usage,
            int iterations,
            List<String> toolsCalled,
            String model,
            String promptVersion,
            String traceId,
            List<Action> actions,
            List<String> flags) {
    }
}
