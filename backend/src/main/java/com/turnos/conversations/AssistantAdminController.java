package com.turnos.conversations;

import com.turnos.assistantconfig.AssistantConfigRequest;
import com.turnos.assistantconfig.AssistantConfigResponse;
import com.turnos.assistantconfig.AssistantConfigService;
import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The owner's "Asistente" screen: configuration (versioned), the playground,
// the conversations the assistant had, and what it cost this month.
@RestController
@RequestMapping("/api/assistant")
public class AssistantAdminController {

    private final AssistantConfigService configService;
    private final ConversationService conversationService;
    private final AssistantInsightsService insightsService;
    private final AiServiceClient aiServiceClient;

    public AssistantAdminController(
            AssistantConfigService configService,
            ConversationService conversationService,
            AssistantInsightsService insightsService,
            AiServiceClient aiServiceClient) {
        this.configService = configService;
        this.conversationService = conversationService;
        this.insightsService = insightsService;
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/config")
    public AssistantConfigResponse config(@AuthenticationPrincipal AuthenticatedPrincipal admin) {
        return configService.get(admin.tenantId());
    }

    @PutMapping("/config/draft")
    public AssistantConfigResponse saveDraft(
            @AuthenticationPrincipal AuthenticatedPrincipal admin, @Valid @RequestBody AssistantConfigRequest request) {
        return configService.saveDraft(admin.tenantId(), admin.id(), request);
    }

    @DeleteMapping("/config/draft")
    public AssistantConfigResponse discardDraft(@AuthenticationPrincipal AuthenticatedPrincipal admin) {
        return configService.discardDraft(admin.tenantId());
    }

    @PostMapping("/config/publish")
    public AssistantConfigResponse publish(@AuthenticationPrincipal AuthenticatedPrincipal admin) {
        return configService.publish(admin.tenantId());
    }

    // The dashboard's chat button calls this on load, so the AI service is
    // awake by the time the owner opens the chat. See AiServiceClient.warmUp.
    @PostMapping("/warmup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void warmUp() {
        aiServiceClient.warmUp();
    }

    // Same pipeline as the public chat, on the draft config, never writing
    // real customers or appointments (AgentBookingService checks the channel).
    @PostMapping("/playground")
    public ConversationService.ChatReply playground(
            @AuthenticationPrincipal AuthenticatedPrincipal admin, @Valid @RequestBody PlaygroundRequest request) {
        return conversationService.handleMessage(
                admin.tenantId(),
                ConversationService.CHANNEL_PLAYGROUND,
                request.sessionId().toString(),
                request.message().strip(),
                null);
    }

    @GetMapping("/conversations")
    public List<AssistantInsightsService.ConversationSummary> conversations(
            @AuthenticationPrincipal AuthenticatedPrincipal admin, @RequestParam(required = false) String mode) {
        return insightsService.conversations(admin.tenantId(), mode);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<AssistantInsightsService.MessageView> messages(
            @AuthenticationPrincipal AuthenticatedPrincipal admin, @PathVariable UUID id) {
        return insightsService.messages(admin.tenantId(), id);
    }

    // Hand a HUMAN conversation back to the assistant.
    @PostMapping("/conversations/{id}/resume")
    public void resume(@AuthenticationPrincipal AuthenticatedPrincipal admin, @PathVariable UUID id) {
        insightsService.resume(admin.tenantId(), id);
    }

    @GetMapping("/usage")
    public AssistantInsightsService.Usage usage(@AuthenticationPrincipal AuthenticatedPrincipal admin) {
        return insightsService.usage(admin.tenantId());
    }

    public record PlaygroundRequest(@NotNull UUID sessionId, @NotBlank @Size(max = 1000) String message) {
    }
}
