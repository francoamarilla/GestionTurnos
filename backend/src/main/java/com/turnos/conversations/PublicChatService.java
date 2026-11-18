package com.turnos.conversations;

import com.turnos.appointments.AppointmentService;
import com.turnos.assistantconfig.AssistantConfig;
import com.turnos.assistantconfig.AssistantConfigService;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.tenancy.TenantStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// The small public reads/writes around the chat that don't involve the AI.
@Service
public class PublicChatService {

    private final TenantRepository tenantRepository;
    private final TenantSessionContext tenantSessionContext;
    private final AssistantConfigService configService;
    private final ConversationRepository conversationRepository;
    private final AppointmentService appointmentService;
    private final ChatMessageRepository chatMessageRepository;

    public PublicChatService(
            TenantRepository tenantRepository,
            TenantSessionContext tenantSessionContext,
            AssistantConfigService configService,
            ConversationRepository conversationRepository,
            AppointmentService appointmentService,
            ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSessionContext = tenantSessionContext;
        this.configService = configService;
        this.conversationRepository = conversationRepository;
        this.appointmentService = appointmentService;
    }

    public record ChatInfo(String businessName, String assistantName, String greeting, boolean captchaRequired) {
    }

    public ChatInfo info(UUID tenantId, boolean captchaRequired) {
        Tenant tenant = tenant(tenantId);
        AssistantConfig config = configService.published(tenantId);
        return new ChatInfo(tenant.getBusinessName(), config.getAssistantName(), config.getGreeting(), captchaRequired);
    }

    public record HistoryMessage(String role, String content, java.time.Instant createdAt) {
    }

    // The thread as the AI sees it, so the screen never shows a "fresh" chat
    // while the model is answering an older conversation. The session id is
    // a random UUID only that browser knows.
    public java.util.List<HistoryMessage> history(UUID tenantId, String sessionId) {
        tenant(tenantId);
        return conversationRepository
                .findByTenantIdAndChannelAndExternalId(tenantId, ConversationService.CHANNEL_WEB, sessionId)
                .map(c -> chatMessageRepository.findByConversationIdOrderByIdAsc(c.getId()).stream()
                        .map(m -> new HistoryMessage(m.getRole(), m.getContent(), m.getCreatedAt()))
                        .toList())
                .orElse(java.util.List.of());
    }

    // Only the customer this chat identified can cancel, and only their own
    // appointment, within the business's cancellation window.
    public String cancelFromChat(UUID tenantId, String sessionId, UUID appointmentId) {
        tenant(tenantId);
        Conversation conversation = conversationRepository
                .findByTenantIdAndChannelAndExternalId(tenantId, ConversationService.CHANNEL_WEB, sessionId)
                .filter(c -> c.getCustomerId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found"));
        try {
            appointmentService.cancelAsCustomer(tenantId, conversation.getCustomerId(), appointmentId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN || e.getStatusCode() == HttpStatus.CONFLICT) {
                return "No se pudo cancelar desde acá: ya pasó el plazo de cancelación. Comunicate con el local.";
            }
            throw e;
        }
        return "Listo, cancelamos el turno.";
    }

    private Tenant tenant(UUID tenantId) {
        tenantSessionContext.apply(false, tenantId);
        return tenantRepository
                .findById(tenantId)
                .filter(t -> t.getStatus() != TenantStatus.SUSPENDED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }
}
