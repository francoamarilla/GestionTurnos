package com.turnos.conversations;

import com.turnos.assistantconfig.AssistantConfigService;
import com.turnos.customers.CustomerRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// What the owner sees about the assistant: conversations (not internal
// traces) and this month's spend against the budget.
@Service
public class AssistantInsightsService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CustomerRepository customerRepository;
    private final AiTurnRepository aiTurnRepository;
    private final TenantRepository tenantRepository;
    private final AssistantConfigService configService;
    private final ConversationService conversationService;

    public AssistantInsightsService(
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            CustomerRepository customerRepository,
            AiTurnRepository aiTurnRepository,
            TenantRepository tenantRepository,
            AssistantConfigService configService,
            ConversationService conversationService) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.customerRepository = customerRepository;
        this.aiTurnRepository = aiTurnRepository;
        this.tenantRepository = tenantRepository;
        this.configService = configService;
        this.conversationService = conversationService;
    }

    public record ConversationSummary(
            UUID id,
            String channel,
            String mode,
            String customerName,
            String customerPhone,
            String lastMessage,
            Instant lastMessageAt,
            String handoffReason) {
    }

    public record MessageView(String role, String content, Instant createdAt) {
    }

    public record Usage(
            BigDecimal monthCostUsd,
            BigDecimal monthlyBudgetUsd,
            int percentOfBudget,
            boolean nearLimit,
            boolean overLimit,
            long turns,
            long bookings,
            long handoffs) {
    }

    public List<ConversationSummary> conversations(UUID tenantId, String mode) {
        var page = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "lastMessageAt"));
        var rows = mode == null || mode.isBlank()
                ? conversationRepository.findByTenantIdAndChannel(tenantId, ConversationService.CHANNEL_WEB, page)
                : conversationRepository.findByTenantIdAndChannelAndMode(
                        tenantId, ConversationService.CHANNEL_WEB, mode, page);
        return rows.stream()
                .map(c -> new ConversationSummary(
                        c.getId(),
                        c.getChannel(),
                        c.getMode(),
                        c.getCustomerId() == null
                                ? null
                                : customerRepository.findById(c.getCustomerId()).map(x -> x.getName()).orElse(null),
                        c.getCustomerPhone(),
                        chatMessageRepository.findTop20ByConversationIdOrderByIdDesc(c.getId()).stream()
                                .findFirst()
                                .map(ChatMessage::getContent)
                                .orElse(""),
                        c.getLastMessageAt(),
                        c.getHandoffReason()))
                .toList();
    }

    public List<MessageView> messages(UUID tenantId, UUID conversationId) {
        Conversation c = owned(tenantId, conversationId);
        return chatMessageRepository.findByConversationIdOrderByIdAsc(c.getId()).stream()
                .map(m -> new MessageView(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
    }

    public void resume(UUID tenantId, UUID conversationId) {
        Conversation c = owned(tenantId, conversationId);
        c.setMode("AI");
        conversationRepository.save(c);
    }

    public Usage usage(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Instant monthStart = ZonedDateTime.now(zone).withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant();
        BigDecimal cost = conversationService.monthCost(tenant);
        BigDecimal budget = configService.published(tenantId).getMonthlyBudgetUsd();
        int percent = budget.signum() == 0
                ? 100
                : cost.multiply(BigDecimal.valueOf(100)).divide(budget, 0, RoundingMode.HALF_UP).intValue();
        return new Usage(
                cost.setScale(4, RoundingMode.HALF_UP),
                budget,
                percent,
                percent >= 80,
                percent >= 100,
                aiTurnRepository.countByTenantIdAndCreatedAtGreaterThanEqual(tenantId, monthStart),
                aiTurnRepository.countByTenantIdAndOutcomeAndCreatedAtGreaterThanEqual(tenantId, "BOOKED", monthStart),
                aiTurnRepository.countByTenantIdAndOutcomeAndCreatedAtGreaterThanEqual(tenantId, "HANDOFF", monthStart));
    }

    private Conversation owned(UUID tenantId, UUID conversationId) {
        return conversationRepository
                .findById(conversationId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }
}
