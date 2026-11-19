package com.turnos.conversations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Public (permitAll): anyone with the business link can chat. The widget
// generates sessionId once and keeps it, which is what ties messages into
// one conversation. Every message can cost an LLM call, hence the rate
// limit per IP and per session, the captcha on new sessions (when
// configured) and the budgets checked in ConversationService.
@RestController
public class PublicChatController {

    private final ConversationService conversationService;
    private final PublicChatService publicChatService;
    private final ChatRateLimiter rateLimiter;
    private final TurnstileVerifier turnstileVerifier;
    private final AiServiceClient aiServiceClient;

    public PublicChatController(
            ConversationService conversationService,
            PublicChatService publicChatService,
            ChatRateLimiter rateLimiter,
            TurnstileVerifier turnstileVerifier,
            AiServiceClient aiServiceClient) {
        this.conversationService = conversationService;
        this.publicChatService = publicChatService;
        this.rateLimiter = rateLimiter;
        this.turnstileVerifier = turnstileVerifier;
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/api/public/{tenantId}/chat/info")
    public PublicChatService.ChatInfo info(@PathVariable UUID tenantId) {
        // The widget loads this when the chat opens.
        aiServiceClient.warmUp();
        return publicChatService.info(tenantId, turnstileVerifier.enabled());
    }

    @GetMapping("/api/public/{tenantId}/chat/history")
    public java.util.List<PublicChatService.HistoryMessage> history(
            @PathVariable UUID tenantId,
            @org.springframework.web.bind.annotation.RequestParam UUID sessionId,
            HttpServletRequest http) {
        rateLimiter.check("ip:" + http.getRemoteAddr());
        return publicChatService.history(tenantId, sessionId.toString());
    }

    @PostMapping("/api/public/{tenantId}/chat")
    public ConversationService.ChatReply chat(
            @PathVariable UUID tenantId, @Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        String message = request.message() == null ? null : request.message().strip();
        if ((message == null || message.isEmpty()) && request.selectionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message or selectionId is required");
        }
        rateLimiter.check("ip:" + http.getRemoteAddr(), "session:" + request.sessionId());
        String sessionId = request.sessionId().toString();
        if (turnstileVerifier.enabled()
                && conversationService.isNewSession(tenantId, ConversationService.CHANNEL_WEB, sessionId)
                && !turnstileVerifier.verify(request.turnstileToken(), http.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Captcha check failed");
        }
        return conversationService.handleMessage(
                tenantId, ConversationService.CHANNEL_WEB, sessionId, message, request.selectionId());
    }

    // The "Cancelar este turno" button under an automatic booking (propuesta
    // section 6.2): undoing a wrong AUTO booking is one tap.
    @PostMapping("/api/public/{tenantId}/chat/cancel")
    public CancelResponse cancel(
            @PathVariable UUID tenantId, @Valid @RequestBody CancelRequest request, HttpServletRequest http) {
        rateLimiter.check("ip:" + http.getRemoteAddr());
        return new CancelResponse(
                publicChatService.cancelFromChat(tenantId, request.sessionId().toString(), request.appointmentId()));
    }

    public record ChatRequest(
            @NotNull UUID sessionId,
            @Size(max = 1000) String message,
            @Size(max = 200) String selectionId,
            @Size(max = 4000) String turnstileToken) {
    }

    public record CancelRequest(@NotNull UUID sessionId, @NotNull UUID appointmentId) {
    }

    public record CancelResponse(String reply) {
    }
}
