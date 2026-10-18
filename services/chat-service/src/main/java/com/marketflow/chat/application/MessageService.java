package com.marketflow.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketflow.chat.api.ChatDtos.MessageEvent;
import com.marketflow.chat.api.ChatDtos.MessagePage;
import com.marketflow.chat.api.ChatDtos.MessageView;
import com.marketflow.chat.api.ChatDtos.SendMessageRequest;
import com.marketflow.chat.api.ChatDtos.TypingEvent;
import com.marketflow.chat.application.ConversationService.Loaded;
import com.marketflow.chat.config.ChatProperties;
import com.marketflow.chat.domain.Access;
import com.marketflow.chat.domain.ChatAccess;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.chat.domain.ContentPolicy;
import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.domain.Message;
import com.marketflow.chat.domain.MessageType;
import com.marketflow.chat.domain.Side;
import com.marketflow.chat.domain.VendorRef;
import com.marketflow.chat.persistence.ConversationRepository;
import com.marketflow.chat.persistence.MessageRepository;
import com.marketflow.chat.persistence.MessageRepository.NewMessage;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.chat.persistence.SupportRepository;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sending, editing, deleting and listing messages, plus typing indicators. REST and STOMP both call these
 * methods, so every rule applies to both transports.
 *
 * <p>A send locks the conversation row ({@code SELECT ... FOR UPDATE}). Messages of one conversation are therefore
 * inserted one at a time, their identity ids increase in commit order, and a client that resynchronises with
 * {@code after=<last id it has>} can never miss a message that commits later with a smaller id.
 */
@Service
public class MessageService {

    public static final String MESSAGES = "/queue/messages";
    public static final String TYPING = "/queue/typing";

    /** Result of a send. {@code duplicate} is true when the client id was already used (a retry). */
    public record Sent(MessageView message, boolean duplicate) {
    }

    private final ConversationService conversationService;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ProjectionRepository projections;
    private final SupportRepository support;
    private final ContentPolicy policy;
    private final RateLimiter rateLimiter;
    private final ChatFanout fanout;
    private final OutboxWriter outbox;
    private final ChatProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    public MessageService(ConversationService conversationService, ConversationRepository conversations,
                          MessageRepository messages, ProjectionRepository projections, SupportRepository support,
                          ContentPolicy policy, RateLimiter rateLimiter, ChatFanout fanout, OutboxWriter outbox,
                          ChatProperties props, ObjectMapper json, Clock clock, MeterRegistry meters) {
        this.conversationService = conversationService;
        this.conversations = conversations;
        this.messages = messages;
        this.projections = projections;
        this.support = support;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.fanout = fanout;
        this.outbox = outbox;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional(noRollbackFor = ContentRejectedException.class)
    public Sent send(ChatActor actor, UUID conversationId, SendMessageRequest req) {
        Conversation c = conversations.findForUpdate(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation"));
        VendorRef vendor = projections.vendor(c.vendorId()).orElse(null);
        Access access = ChatAccess.write(actor, c, vendor);
        if (req.type() == null || !req.type().sendableByPeople()) {
            throw ApiException.badRequest("invalid_type", "This kind of message cannot be sent");
        }
        if (req.clientMessageId() == null) {
            throw ApiException.badRequest("validation_failed", "clientMessageId is required");
        }

        // Idempotency first: a retry of an already stored message is answered without counting against the
        // rate limit and without re-running the content checks.
        Optional<Message> existing = messages.findByClientId(actor.userId(), req.clientMessageId());
        if (existing.isPresent()) {
            return duplicate(actor, existing.get(), conversationId);
        }
        if (!rateLimiter.tryAcquireMessage(actor.userId())) {
            meters.counter("chat.messages.rejected", "reason", "rate_limited").increment();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests",
                    "You are sending messages too quickly. Wait a few seconds and try again.");
        }

        Draft d = draft(actor, c, req);
        Instant now = clock.instant();
        Side side = access.side();
        Optional<Message> inserted = messages.insert(new NewMessage(c.id(), actor.userId(), side.name(), req.type(),
                d.body(), d.attachmentUrl(), d.metadata(), req.clientMessageId(), now));
        if (inserted.isEmpty()) {
            // a concurrent retry with the same client id won the insert
            return duplicate(actor, messages.findByClientId(actor.userId(), req.clientMessageId()).orElseThrow(),
                    conversationId);
        }
        Message m = inserted.get();
        conversations.touch(c.id(), m.id(), m.createdAt());
        conversations.markRead(c.id(), actor.userId(), m.id());

        List<UUID> recipients = conversationService.participantIds(c.id(), side.other(), true);
        if (!recipients.isEmpty()) {
            outbox.append(ChatEvents.TOPIC, "ChatMessageSent", 1, ChatEvents.AGGREGATE, c.id().toString(),
                    new ChatEvents.ChatMessageSent(c.id(), m.id(), c.vendorId(), c.buyerId(), side.name(),
                            side == Side.BUYER ? c.buyerName() : vendor.businessName(), recipients,
                            side.other().name(), m.type().name(), c.productId(), c.suborderId(), m.createdAt()));
        }
        MessageView view = ChatViews.message(m, false);
        fanout.toUsers(conversationService.participantIds(c.id()), MESSAGES,
                new MessageEvent("MESSAGE_CREATED", c.id(), view, null));
        meters.counter("chat.messages.sent", "type", m.type().name(), "side", side.name()).increment();
        return new Sent(view, false);
    }

    private Sent duplicate(ChatActor actor, Message m, UUID conversationId) {
        if (!m.conversationId().equals(conversationId)) {
            throw ApiException.conflict("client_message_id_reused",
                    "This client message id was already used in another conversation");
        }
        MessageView view = ChatViews.message(m, false);
        // the original delivery may be what the client missed: repeat it to the sender's own sessions only
        fanout.toUsers(List.of(actor.userId()), MESSAGES, new MessageEvent("MESSAGE_CREATED", conversationId, view, null));
        meters.counter("chat.messages.duplicates").increment();
        return new Sent(view, true);
    }

    private record Draft(String body, String attachmentUrl, ObjectNode metadata) {
    }

    private Draft draft(ChatActor actor, Conversation c, SendMessageRequest req) {
        return switch (req.type()) {
            case TEXT -> new Draft(checkedText(actor, c, req.body(), true), null, null);
            case IMAGE -> {
                if (req.attachmentId() == null) {
                    throw ApiException.badRequest("validation_failed", "attachmentId is required for an image");
                }
                SupportRepository.AttachmentMeta a = support.attachmentMeta(req.attachmentId())
                        .filter(x -> x.conversationId().equals(c.id()) && x.uploaderId().equals(actor.userId()))
                        .orElseThrow(() -> ApiException.notFound("Attachment"));
                ObjectNode meta = json.createObjectNode().put("attachmentId", a.id().toString())
                        .put("contentType", a.contentType()).put("sizeBytes", a.sizeBytes());
                yield new Draft(checkedText(actor, c, req.body(), false), AttachmentService.url(a.id()), meta);
            }
            case PRODUCT_CARD -> {
                if (req.productId() == null) {
                    throw ApiException.badRequest("validation_failed", "productId is required for a product card");
                }
                ProjectionRepository.ProductRef p = projections.product(req.productId())
                        .filter(x -> x.vendorId().equals(c.vendorId()))
                        .orElseThrow(() -> ApiException.notFound("Product"));
                ObjectNode meta = json.createObjectNode().put("productId", p.productId().toString())
                        .put("name", p.name()).put("slug", p.slug()).put("imageUrl", p.imageUrl())
                        .put("currency", p.currency());
                meta.put("price", p.minPrice());
                yield new Draft(checkedText(actor, c, req.body(), false), null, meta);
            }
            case ORDER_CARD -> {
                if (req.suborderId() == null) {
                    throw ApiException.badRequest("validation_failed", "suborderId is required for an order card");
                }
                ProjectionRepository.SuborderRef s = projections.suborder(req.suborderId())
                        .filter(x -> x.customerId().equals(c.buyerId()) && x.vendorId().equals(c.vendorId()))
                        .orElseThrow(() -> ApiException.notFound("Order"));
                ObjectNode meta = json.createObjectNode().put("orderId", s.orderId().toString())
                        .put("suborderId", s.suborderId().toString()).put("status", s.status())
                        .put("itemsSummary", ChatViews.itemsSummary(s.items())).put("currency", s.currency());
                meta.put("total", s.total());
                yield new Draft(checkedText(actor, c, req.body(), false), null, meta);
            }
            case SYSTEM -> throw ApiException.badRequest("invalid_type", "This kind of message cannot be sent");
        };
    }

    /** Shape rules, then the contact-information policy; a refusal is recorded for administrators. */
    private String checkedText(ChatActor actor, Conversation c, String raw, boolean required) {
        String text;
        try {
            text = policy.normaliseText(raw, required);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_message", e.getMessage());
        }
        ContentPolicy.Result r = policy.check(text);
        if (!r.allowed()) {
            String kinds = r.violations().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
            support.recordViolation(c.id(), actor.userId(), kinds);
            r.violations().forEach(v -> meters.counter("chat.messages.rejected", "reason", v.name()).increment());
            throw new ContentRejectedException(r.violations());
        }
        return text;
    }

    // ---------- edit / delete ----------

    @Transactional(noRollbackFor = ContentRejectedException.class)
    public MessageView edit(ChatActor actor, long messageId, String body) {
        Changeable x = changeable(actor, messageId, true);
        String text = checkedText(actor, x.conversation(), body, true);
        Message m = messages.edit(messageId, text, clock.instant())
                .orElseThrow(() -> ApiException.conflict("message_deleted", "This message was deleted"));
        return broadcastUpdate(m);
    }

    @Transactional
    public MessageView delete(ChatActor actor, long messageId) {
        changeable(actor, messageId, false);
        Message m = messages.softDelete(messageId, clock.instant())
                .orElseThrow(() -> ApiException.conflict("message_deleted", "This message was already deleted"));
        return broadcastUpdate(m);
    }

    private record Changeable(Conversation conversation, Message message) {
    }

    private Changeable changeable(ChatActor actor, long messageId, boolean edit) {
        Message m = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("Message"));
        Conversation c = conversations.findById(m.conversationId()).orElseThrow();
        VendorRef vendor = projections.vendor(c.vendorId()).orElse(null);
        ChatAccess.write(actor, c, vendor);
        if (m.senderId() == null || !m.senderId().equals(actor.userId())) {
            throw ApiException.forbidden("You can only change your own messages");
        }
        if (m.deleted()) {
            throw ApiException.conflict("message_deleted", "This message was deleted");
        }
        if (edit && m.type() != MessageType.TEXT) {
            throw ApiException.unprocessable("not_editable", "Only text messages can be edited");
        }
        if (!m.changeableBy(actor.userId(), clock.instant(), props.editWindow(), edit)) {
            throw ApiException.conflict("edit_window_passed", "Messages can only be changed for "
                    + props.editWindow().toMinutes() + " minutes after sending");
        }
        return new Changeable(c, m);
    }

    private MessageView broadcastUpdate(Message m) {
        MessageView view = ChatViews.message(m, false);
        fanout.toUsers(conversationService.participantIds(m.conversationId()), MESSAGES,
                new MessageEvent("MESSAGE_UPDATED", m.conversationId(), view, null));
        return view;
    }

    // ---------- reading ----------

    /**
     * Timeline page. {@code before}: older messages for upward infinite scroll. {@code after}: everything newer
     * than what the client has (resync after a reconnect), plus messages edited or deleted since {@code since}.
     */
    @Transactional(readOnly = true)
    public MessagePage page(ChatActor actor, UUID conversationId, Long before, Long after, Instant since, int limit) {
        Loaded l = conversationService.load(actor, conversationId);
        boolean reveal = !l.access().participant();
        if (after != null) {
            List<Message> rows = messages.after(conversationId, after, limit + 1);
            boolean more = rows.size() > limit;
            List<MessageView> items = (more ? rows.subList(0, limit) : rows).stream()
                    .map(m -> ChatViews.message(m, reveal)).toList();
            List<MessageView> changed = since == null ? List.of() : messages.changedSince(conversationId, since, 200)
                    .stream().filter(m -> m.id() <= after).map(m -> ChatViews.message(m, reveal)).toList();
            return new MessagePage(items, more, changed);
        }
        List<Message> rows = messages.before(conversationId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<Message> page = more ? rows.subList(1, rows.size()) : rows;
        return new MessagePage(page.stream().map(m -> ChatViews.message(m, reveal)).toList(), more, List.of());
    }

    // ---------- typing ----------

    /** Not stored; delivered to the other side only. "Stopped typing" is never throttled. */
    @Transactional(readOnly = true)
    public void typing(ChatActor actor, UUID conversationId, boolean typing) {
        Loaded l = conversationService.load(actor, conversationId);
        if (!l.access().participant() || !l.access().canWrite()) {
            return;
        }
        if (typing && !rateLimiter.tryTyping(actor.userId(), conversationId)) {
            return;
        }
        Side side = l.access().side();
        String name = side == Side.BUYER ? l.conversation().buyerName()
                : l.vendor() == null ? "Seller" : l.vendor().businessName();
        fanout.toUsers(conversationService.participantIds(conversationId, side.other(), false), TYPING,
                new TypingEvent(conversationId, side.name(), name, typing));
    }

    // ---------- system messages ----------

    /** Written by the order-status consumer inside its (inbox) transaction; never produces a notification. */
    @Transactional
    public Message postSystem(Conversation c, String text, ObjectNode metadata) {
        Message m = messages.insert(new NewMessage(c.id(), null, "SYSTEM", MessageType.SYSTEM, text, null, metadata,
                null, clock.instant())).orElseThrow();
        conversations.touch(c.id(), m.id(), m.createdAt());
        fanout.toUsers(conversationService.participantIds(c.id()), MESSAGES,
                new MessageEvent("MESSAGE_CREATED", c.id(), ChatViews.message(m, false), null));
        meters.counter("chat.messages.sent", "type", "SYSTEM", "side", "SYSTEM").increment();
        return m;
    }
}
