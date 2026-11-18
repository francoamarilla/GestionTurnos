package com.turnos.agentapi;

import com.turnos.assistantconfig.AssistantConfig;
import com.turnos.assistantconfig.AssistantConfigService;
import com.turnos.conversations.Conversation;
import com.turnos.conversations.ConversationRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// The owner's switches, enforced server-side: even if the prompt filtering
// failed and the model called a disabled tool, Spring refuses it here.
// Playground conversations run on the DRAFT config; real ones on PUBLISHED.
@Component
public class AgentPolicy {

    public enum Capability {
        BOOK,
        CANCEL,
        RESCHEDULE
    }

    private final AssistantConfigService configService;
    private final ConversationRepository conversationRepository;

    public AgentPolicy(AssistantConfigService configService, ConversationRepository conversationRepository) {
        this.configService = configService;
        this.conversationRepository = conversationRepository;
    }

    public AssistantConfig config(UUID tenantId, UUID conversationId) {
        boolean playground = conversationRepository
                .findById(conversationId)
                .map(Conversation::isPlayground)
                .orElse(false);
        return playground ? configService.draftOrPublished(tenantId) : configService.published(tenantId);
    }

    public void require(UUID tenantId, UUID conversationId, Capability capability) {
        AssistantConfig c = config(tenantId, conversationId);
        boolean allowed = switch (capability) {
            case BOOK -> c.isCanBook();
            case CANCEL -> c.isCanCancel();
            case RESCHEDULE -> c.isCanReschedule();
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The business turned this off: " + capability);
        }
    }

    public String confirmationMode(UUID tenantId, UUID conversationId) {
        return config(tenantId, conversationId).getConfirmationMode();
    }
}
