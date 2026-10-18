package com.marketflow.chat.application;

import com.marketflow.chat.api.ChatDtos.ConversationSummary;
import com.marketflow.chat.api.ChatDtos.ConversationView;
import com.marketflow.chat.api.ChatDtos.CursorPage;
import com.marketflow.chat.api.ChatDtos.ReceiptEvent;
import com.marketflow.chat.api.ChatDtos.StartConversationRequest;
import com.marketflow.chat.api.ChatDtos.StartResult;
import com.marketflow.chat.api.ChatDtos.UnreadCounts;
import com.marketflow.chat.domain.Access;
import com.marketflow.chat.domain.ChatAccess;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.domain.Side;
import com.marketflow.chat.domain.VendorRef;
import com.marketflow.chat.persistence.ConversationRepository;
import com.marketflow.chat.persistence.ConversationRepository.InboxRow;
import com.marketflow.chat.persistence.ConversationRepository.Participant;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ProjectionRepository projections;
    private final ChatViews views;
    private final ChatFanout fanout;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, ProjectionRepository projections,
                               ChatViews views, ChatFanout fanout, Clock clock) {
        this.conversations = conversations;
        this.projections = projections;
        this.views = views;
        this.fanout = fanout;
        this.clock = clock;
    }

    /** Loaded conversation plus what the actor may do in it. */
    public record Loaded(Conversation conversation, VendorRef vendor, Access access) {
    }

    public Loaded load(ChatActor actor, UUID conversationId) {
        Conversation c = conversations.findById(conversationId).orElseThrow(() -> ApiException.notFound("Conversation"));
        VendorRef vendor = projections.vendor(c.vendorId()).orElse(null);
        return new Loaded(c, vendor, ChatAccess.read(actor, c, vendor));
    }

    /**
     * Opens the conversation for (buyer, shop, context), or returns the existing one. Concurrent first calls
     * converge on one row through the unique context constraint.
     */
    @Transactional
    public StartResult start(ChatActor actor, StartConversationRequest req) {
        if (req.productId() != null && req.suborderId() != null) {
            throw ApiException.badRequest("invalid_context", "A conversation is about a product or an order, not both");
        }
        VendorRef vendor = projections.vendor(req.vendorId()).orElse(null);
        ChatAccess.requireCanStart(actor, vendor);
        if (req.productId() != null) {
            projections.product(req.productId())
                    .filter(p -> p.vendorId().equals(vendor.vendorId()) && p.visible())
                    .orElseThrow(() -> ApiException.notFound("Product"));
        }
        if (req.suborderId() != null) {
            projections.suborder(req.suborderId())
                    .filter(s -> s.customerId().equals(actor.userId()) && s.vendorId().equals(vendor.vendorId()))
                    .orElseThrow(() -> ApiException.notFound("Order"));
        }
        boolean created = conversations.insertIfAbsent(UUID.randomUUID(), actor.userId(), actor.displayName(),
                vendor.vendorId(), req.productId(), req.suborderId(), clock.instant());
        Conversation c = conversations.findByContext(actor.userId(), vendor.vendorId(), req.productId(),
                req.suborderId()).orElseThrow();
        conversations.addParticipant(c.id(), c.buyerId(), Side.BUYER);
        conversations.addParticipant(c.id(), vendor.ownerUserId(), Side.VENDOR);
        Access access = ChatAccess.read(actor, c, vendor);
        return new StartResult(views.conversation(c, access, vendor, actor.userId()), created);
    }

    @Transactional(readOnly = true)
    public ConversationView get(ChatActor actor, UUID conversationId) {
        Loaded l = load(actor, conversationId);
        return views.conversation(l.conversation(), l.access(), l.vendor(), actor.userId());
    }

    @Transactional(readOnly = true)
    public CursorPage<ConversationSummary> inbox(ChatActor actor, Side side, String cursor, int limit) {
        if (side == Side.VENDOR && projections.vendorOwnedBy(actor.userId()).isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        Cursor c = Cursor.decode(cursor);
        List<InboxRow> rows = conversations.inbox(actor.userId(), side, c == null ? null : c.at(),
                c == null ? null : c.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<InboxRow> page = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.encode(page.getLast().conversation().lastMessageAt(),
                page.getLast().conversation().id()) : null;
        return new CursorPage<>(page.stream().map(r -> views.summary(r, side)).toList(), next);
    }

    @Transactional(readOnly = true)
    public UnreadCounts unread(ChatActor actor) {
        return new UnreadCounts(conversations.unreadTotal(actor.userId(), Side.BUYER),
                conversations.unreadTotal(actor.userId(), Side.VENDOR));
    }

    /**
     * Moves the reader's marker forward (never past the newest message) and tells both sides: the other side
     * updates its "seen" ticks, the reader's other tabs clear their unread badges.
     */
    @Transactional
    public long markRead(ChatActor actor, UUID conversationId, Long lastReadMessageId) {
        Loaded l = load(actor, conversationId);
        if (!l.access().participant()) {
            throw ApiException.forbidden("Administrators can read conversations but not take part in them");
        }
        Conversation c = l.conversation();
        long newest = c.lastMessageId() == null ? 0 : c.lastMessageId();
        long target = lastReadMessageId == null ? newest : Math.min(lastReadMessageId, newest);
        long now = conversations.markRead(c.id(), actor.userId(), target);
        Side side = l.access().side();
        long sideRead = conversations.lastReadBySide(c.id(), side);
        fanout.toUsers(participantIds(c.id()), "/queue/receipts", new ReceiptEvent(c.id(), side.name(), sideRead));
        return now;
    }

    @Transactional
    public ConversationView mute(ChatActor actor, UUID conversationId, boolean muted) {
        Loaded l = load(actor, conversationId);
        if (!l.access().participant()) {
            throw ApiException.forbidden("Administrators can read conversations but not take part in them");
        }
        conversations.setMuted(conversationId, actor.userId(), muted);
        return views.conversation(l.conversation(), l.access(), l.vendor(), actor.userId());
    }

    public List<UUID> participantIds(UUID conversationId) {
        return conversations.participants(conversationId).stream().map(Participant::userId).toList();
    }

    public List<UUID> participantIds(UUID conversationId, Side side, boolean skipMuted) {
        return conversations.participants(conversationId).stream()
                .filter(p -> p.side() == side && !(skipMuted && p.muted())).map(Participant::userId).toList();
    }

    /** Opaque keyset cursor over (last_message_at, id). */
    public record Cursor(Instant at, UUID id) {

        public static String encode(Instant at, UUID id) {
            String raw = at.getEpochSecond() + "." + at.getNano() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        public static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int colon = raw.indexOf(':');
                String[] time = raw.substring(0, colon).split("\\.");
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])),
                        UUID.fromString(raw.substring(colon + 1)));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("invalid_cursor", "The cursor is not valid");
            }
        }
    }
}
