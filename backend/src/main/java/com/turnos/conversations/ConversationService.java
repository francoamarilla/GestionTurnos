package com.turnos.conversations;

import com.turnos.agentapi.AgentBookingService;
import com.turnos.agentapi.PendingAction;
import com.turnos.assistantconfig.AssistantConfig;
import com.turnos.assistantconfig.AssistantConfigService;
import com.turnos.auth.JwtService;
import com.turnos.bot.BotConversationService;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.tenancy.TenantStatus;
import com.turnos.whatsapp.WhatsAppProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

// One chat message: store it, decide who answers, store the answer.
//
//   HUMAN mode               -> nobody (the owner was told; a fixed notice)
//   button tap / "menú"      -> the button bot (propuesta D4)
//   AI off / over budget     -> the button bot
//   otherwise                -> the AI agent; if it fails, the button bot
//
// The public endpoint has no JWT, so the tenant context is applied here by
// hand (same as the WhatsApp bot); TenantContextFilter resets it when the
// request ends. The AI call runs OUTSIDE any transaction: it takes seconds
// and calls back into /internal/agent/*, which must not wait on our locks.
// ponytail: the request still holds its pooled connection during that call
// (open-in-view), so each chat in flight costs 2 connections; move chat out
// of the open-in-view filter if the pool becomes the bottleneck.
@Service
public class ConversationService {

    static final String CHANNEL_WEB = "WEB";
    public static final String CHANNEL_PLAYGROUND = "PLAYGROUND";
    static final long AGENT_TOKEN_TTL_SECONDS = 300;
    static final String HUMAN_NOTICE =
            "Ya le avisamos al equipo del local: te van a responder lo antes posible.";
    static final String ASK_PHONE =
            "Ahora te atiendo con el menú de opciones. Para empezar, escribime tu número de teléfono.";

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final TenantRepository tenantRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AiTurnRepository aiTurnRepository;
    private final TenantSessionContext tenantSessionContext;
    private final TransactionTemplate transactionTemplate;
    private final JwtService jwtService;
    private final AiServiceClient aiServiceClient;
    private final AssistantConfigService configService;
    private final AgentBookingService bookingService;
    private final BotConversationService botConversationService;
    private final AiSpendGuard spendGuard;

    @PersistenceContext
    private EntityManager entityManager;

    public ConversationService(
            TenantRepository tenantRepository,
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            AiTurnRepository aiTurnRepository,
            TenantSessionContext tenantSessionContext,
            TransactionTemplate transactionTemplate,
            JwtService jwtService,
            AiServiceClient aiServiceClient,
            AssistantConfigService configService,
            AgentBookingService bookingService,
            BotConversationService botConversationService,
            AiSpendGuard spendGuard) {
        this.tenantRepository = tenantRepository;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.aiTurnRepository = aiTurnRepository;
        this.tenantSessionContext = tenantSessionContext;
        this.transactionTemplate = transactionTemplate;
        this.jwtService = jwtService;
        this.aiServiceClient = aiServiceClient;
        this.configService = configService;
        this.bookingService = bookingService;
        this.botConversationService = botConversationService;
        this.spendGuard = spendGuard;
    }

    public record ChatReply(
            String reply, List<WhatsAppProvider.Button> options, List<AiServiceClient.Action> actions, String mode) {
    }

    public boolean isNewSession(UUID tenantId, String channel, String sessionId) {
        tenantSessionContext.apply(false, tenantId);
        return conversationRepository.findByTenantIdAndChannelAndExternalId(tenantId, channel, sessionId).isEmpty();
    }

    public ChatReply handleMessage(UUID tenantId, String channel, String sessionId, String text, String selectionId) {
        tenantSessionContext.apply(false, tenantId);
        MDC.put("tenantId", tenantId.toString());

        Tenant tenant = tenantRepository
                .findById(tenantId)
                .filter(t -> t.getStatus() != TenantStatus.SUSPENDED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        boolean playground = CHANNEL_PLAYGROUND.equals(channel);
        AssistantConfig config =
                playground ? configService.draftOrPublished(tenantId) : configService.published(tenantId);

        // ponytail: two first messages racing on the same new session hit the
        // unique constraint and one gets a 500; the widget sends one at a time.
        Conversation conversation = transactionTemplate.execute(status -> {
            Conversation c = conversationRepository
                    .findByTenantIdAndChannelAndExternalId(tenantId, channel, sessionId)
                    .orElseGet(() -> newConversation(tenantId, channel, sessionId));
            c.setLastMessageAt(Instant.now());
            return conversationRepository.save(c);
        });

        List<AiServiceClient.HistoryItem> history = history(conversation.getId());
        String userText = text != null && !text.isBlank() ? text : selectionId;
        save(conversation, "USER", userText);

        ChatReply reply;
        if ("HUMAN".equals(conversation.getMode())) {
            reply = new ChatReply(HUMAN_NOTICE, List.of(), List.of(), "HUMAN");
        } else if (selectionId != null || isMenuRequest(text) || !aiAvailable(tenant, config, playground)) {
            reply = fallback(tenant, conversation, text, selectionId, playground);
        } else {
            reply = askAgent(tenant, config, conversation, history, text, playground);
            // The agent's tool calls (identify_customer, handoff) wrote this
            // row in their own requests: re-read it before touching it again.
            transactionTemplate.executeWithoutResult(status -> entityManager.refresh(conversation));
            if (reply == null) {
                // Same message, now for the bot: it may be the phone it asked for.
                reply = fallback(tenant, conversation, text, null, playground);
            }
        }

        save(conversation, "ASSISTANT", reply.reply());
        if (!reply.mode().equals(conversation.getMode())) {
            conversationRepository.updateMode(conversation.getId(), reply.mode());
        }
        return reply;
    }

    // ---- AI -----------------------------------------------------------------

    private boolean aiAvailable(Tenant tenant, AssistantConfig config, boolean playground) {
        if (!aiServiceClient.enabled() || !config.isAiEnabled() && !playground) {
            return false;
        }
        if (!spendGuard.globalBudgetLeft()) {
            return false;
        }
        return monthCost(tenant).compareTo(config.getMonthlyBudgetUsd()) < 0;
    }

    // null = the agent couldn't answer; the caller falls back.
    private ChatReply askAgent(
            Tenant tenant,
            AssistantConfig config,
            Conversation conversation,
            List<AiServiceClient.HistoryItem> history,
            String text,
            boolean playground) {
        String coreToken = jwtService.generateToken(
                conversation.getId().toString(),
                Map.of("role", "AGENT", "tenantId", tenant.getId().toString()),
                AGENT_TOKEN_TTL_SECONDS);
        PendingAction pending = bookingService.openDraftFor(conversation.getId());
        long started = System.currentTimeMillis();
        AiServiceClient.TurnResponse response;
        try {
            response = aiServiceClient.turn(new AiServiceClient.TurnRequest(
                    text,
                    history,
                    coreToken,
                    tenant.getBusinessName(),
                    tenant.getTimezone(),
                    agentConfig(config, playground),
                    pending == null
                            ? null
                            : new AiServiceClient.PendingInfo(
                                    pending.getId(), pending.getType(), pending.getSummary(), pending.getMode())));
        } catch (RuntimeException e) {
            log.warn("AI service failed for conversation {}", conversation.getId(), e);
            recordTurn(tenant, conversation, config, null, started, "ERROR");
            return null;
        }
        if (response == null || response.reply() == null || response.reply().isBlank()) {
            recordTurn(tenant, conversation, config, response, started, "FALLBACK");
            return null;
        }
        List<AiServiceClient.Action> actions = response.actions() == null ? List.of() : response.actions();
        boolean handoff = actions.stream().anyMatch(a -> "handoff".equals(a.type()));
        recordTurn(tenant, conversation, config, response, started, outcome(actions));
        return new ChatReply(response.reply(), List.of(), actions, handoff ? "HUMAN" : "AI");
    }

    private void recordTurn(
            Tenant tenant,
            Conversation conversation,
            AssistantConfig config,
            AiServiceClient.TurnResponse response,
            long started,
            String outcome) {
        AiTurn turn = new AiTurn();
        turn.setTenantId(tenant.getId());
        turn.setConversationId(conversation.getId());
        turn.setConfigVersion(config.getVersion());
        turn.setLatencyMs((int) (System.currentTimeMillis() - started));
        turn.setOutcome(outcome);
        if (response != null) {
            turn.setModel(response.model() == null ? "?" : response.model());
            turn.setPromptVersion(response.promptVersion() == null ? "?" : response.promptVersion());
            turn.setIterations(response.iterations());
            turn.setTraceId(response.traceId());
            if (response.usage() != null) {
                turn.setInputTokens(response.usage().inputTokens());
                turn.setOutputTokens(response.usage().outputTokens());
                turn.setCachedTokens(response.usage().cacheReadTokens());
                BigDecimal cost = response.usage().costUsd() == null ? BigDecimal.ZERO : response.usage().costUsd();
                turn.setCostUsd(cost);
                spendGuard.add(cost);
            }
        } else {
            turn.setModel("?");
            turn.setPromptVersion("?");
        }
        aiTurnRepository.save(turn);
    }

    private static String outcome(List<AiServiceClient.Action> actions) {
        for (var a : actions) {
            switch (a.type()) {
                case "handoff" -> {
                    return "HANDOFF";
                }
                case "booking_created" -> {
                    return "BOOKED";
                }
                case "booking_cancelled" -> {
                    return "CANCELLED";
                }
                case "booking_rescheduled" -> {
                    return "RESCHEDULED";
                }
                default -> {
                    // keep looking
                }
            }
        }
        return "REPLIED";
    }

    public BigDecimal monthCost(Tenant tenant) {
        Instant monthStart = ZonedDateTime.now(ZoneId.of(tenant.getTimezone()))
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay(ZoneId.of(tenant.getTimezone()))
                .toInstant();
        return aiTurnRepository.costSince(tenant.getId(), monthStart);
    }

    private static AiServiceClient.AgentConfig agentConfig(AssistantConfig c, boolean playground) {
        return new AiServiceClient.AgentConfig(
                c.getVersion(),
                c.getAssistantName(),
                c.getTone(),
                c.isVoseo(),
                c.getEmojiLevel(),
                c.getGreeting(),
                c.getCustomInstructions(),
                c.getPolicies(),
                c.getHandoffInstructions(),
                c.isCanBook(),
                c.isCanCancel(),
                c.isCanReschedule(),
                playground);
    }

    // ---- button bot fallback -----------------------------------------------

    private ChatReply fallback(
            Tenant tenant, Conversation conversation, String text, String selectionId, boolean playground) {
        if (playground) {
            return new ChatReply(
                    "(Playground) Acá el cliente vería el menú de botones: la IA está apagada, sin presupuesto o falló.",
                    List.of(),
                    List.of(),
                    "FALLBACK");
        }
        // The button bot identifies people by phone, like on WhatsApp.
        if (conversation.getCustomerPhone() == null) {
            String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
            if (digits.length() < 8 || digits.length() > 15) {
                return new ChatReply(ASK_PHONE, List.of(), List.of(), "FALLBACK");
            }
            conversation.setCustomerPhone((text.strip().startsWith("+") ? "+" : "") + digits);
            conversationRepository.save(conversation);
            text = null; // the phone itself is not a menu answer
        }
        List<BotConversationService.WebReply> replies = botConversationService.handleWebMessage(
                tenant.getId(), conversation.getCustomerPhone(), selectionId, text);
        tenantSessionContext.apply(false, tenant.getId());
        String joined = String.join("\n\n", replies.stream().map(BotConversationService.WebReply::text).toList());
        List<WhatsAppProvider.Button> options = replies.isEmpty() ? List.of() : replies.getLast().options();
        boolean human = botConversationService.isInHumanHandoff(tenant.getId(), conversation.getCustomerPhone());
        if (human && conversation.getHandoffAt() == null) {
            markHandoff(conversation, "Pidió hablar con una persona (menú)");
        }
        return new ChatReply(joined.isBlank() ? ASK_PHONE : joined, options, List.of(), human ? "HUMAN" : "FALLBACK");
    }

    public void markHandoff(Conversation conversation, String reason) {
        conversation.setMode("HUMAN");
        conversation.setHandoffReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        conversation.setHandoffAt(Instant.now());
        conversationRepository.save(conversation);
    }

    // ---- helpers --------------------------------------------------------------

    private static boolean isMenuRequest(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip().toLowerCase();
        return t.equals("menu") || t.equals("menú");
    }

    private List<AiServiceClient.HistoryItem> history(UUID conversationId) {
        List<ChatMessage> latest =
                new ArrayList<>(chatMessageRepository.findTop20ByConversationIdOrderByIdDesc(conversationId));
        Collections.reverse(latest);
        return latest.stream()
                .map(m -> new AiServiceClient.HistoryItem(m.getRole(), m.getContent()))
                .toList();
    }

    private void save(Conversation conversation, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getId());
        message.setTenantId(conversation.getTenantId());
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        chatMessageRepository.save(message);
    }

    private static Conversation newConversation(UUID tenantId, String channel, String sessionId) {
        Conversation c = new Conversation();
        c.setTenantId(tenantId);
        c.setChannel(channel);
        c.setExternalId(sessionId);
        return c;
    }
}
