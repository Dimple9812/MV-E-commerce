package com.marketflow.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketflow.chat.api.ChatDtos.ContextView;
import com.marketflow.chat.api.ChatDtos.ConversationSummary;
import com.marketflow.chat.api.ChatDtos.ConversationView;
import com.marketflow.chat.api.ChatDtos.CounterpartView;
import com.marketflow.chat.api.ChatDtos.MessagePreview;
import com.marketflow.chat.api.ChatDtos.MessageView;
import com.marketflow.chat.domain.Access;
import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.domain.Message;
import com.marketflow.chat.domain.MessageType;
import com.marketflow.chat.domain.Side;
import com.marketflow.chat.domain.VendorRef;
import com.marketflow.chat.persistence.ConversationRepository;
import com.marketflow.chat.persistence.ConversationRepository.InboxRow;
import com.marketflow.chat.persistence.ConversationRepository.Participant;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.chat.persistence.SupportRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Builds API views; the only place that decides what each side may see of a conversation. */
@Component
public class ChatViews {

    private final ConversationRepository conversations;
    private final ProjectionRepository projections;
    private final SupportRepository support;
    private final Presence presence;

    public ChatViews(ConversationRepository conversations, ProjectionRepository projections, SupportRepository support,
                     Presence presence) {
        this.conversations = conversations;
        this.projections = projections;
        this.support = support;
        this.presence = presence;
    }

    /**
     * @param revealDeleted administrators reviewing a dispute see the text of deleted messages; participants do not
     */
    public static MessageView message(Message m, boolean revealDeleted) {
        boolean hide = m.deleted() && !revealDeleted;
        return new MessageView(m.id(), m.conversationId(), m.senderId(), m.senderRole(), m.type().name(),
                hide ? null : m.body(), hide ? null : m.attachmentUrl(), hide ? null : m.metadata(),
                m.clientMessageId(), m.createdAt(), m.editedAt(), m.deleted());
    }

    public ConversationView conversation(Conversation c, Access access, VendorRef vendor, UUID viewer) {
        List<Participant> people = conversations.participants(c.id());
        Side side = access.side();
        long mine = 0;
        boolean muted = false;
        for (Participant p : people) {
            if (p.userId().equals(viewer)) {
                mine = p.lastReadMessageId();
                muted = p.muted();
            }
        }
        // an administrator is shown the shop as the "other side" (the buyer's perspective)
        Side viewSide = side == null ? Side.BUYER : side;
        long theirs = conversations.lastReadBySide(c.id(), viewSide.other());
        return new ConversationView(c.id(), side == null ? null : side.name(), c.status().name(), c.buyerId(),
                c.buyerName(), c.vendorId(), vendor == null ? "Shop" : vendor.businessName(), context(c),
                counterpart(c, viewSide, vendor, people), mine, theirs, muted, access.canWrite(), c.createdAt(),
                c.lastMessageAt());
    }

    public ConversationSummary summary(InboxRow row, Side side) {
        Conversation c = row.conversation();
        VendorRef vendor = projections.vendor(c.vendorId()).orElse(null);
        MessagePreview preview = row.previewId() == null ? null : new MessagePreview(row.previewId(),
                row.previewType(), previewText(row.previewType(), row.previewBody(), row.previewDeleted()),
                row.previewSenderRole(), row.previewDeleted());
        return new ConversationSummary(c.id(), c.status().name(),
                counterpart(c, side, vendor, conversations.participants(c.id())), context(c), preview,
                row.unread(), row.muted(), c.lastMessageAt());
    }

    CounterpartView counterpart(Conversation c, Side viewerSide, VendorRef vendor, List<Participant> people) {
        if (viewerSide == Side.BUYER) {
            List<UUID> sellers = people.stream().filter(p -> p.side() == Side.VENDOR).map(Participant::userId).toList();
            if (sellers.isEmpty() && vendor != null) {
                sellers = List.of(vendor.ownerUserId());
            }
            Presence.Status s = presence.combined(sellers);
            return new CounterpartView("SHOP", vendor == null ? "Shop" : vendor.businessName(), c.vendorId(),
                    vendor == null ? null : vendor.slug(), sellers, s.online(), s.lastSeenAt(),
                    support.medianReplySeconds(c.vendorId()).orElse(null));
        }
        Presence.Status s = presence.status(c.buyerId());
        return new CounterpartView("BUYER", c.buyerName(), null, null, List.of(c.buyerId()), s.online(),
                s.lastSeenAt(), null);
    }

    public ContextView context(Conversation c) {
        if (c.productId() != null) {
            return projections.product(c.productId())
                    .map(p -> new ContextView("PRODUCT", p.productId(), p.name(), p.imageUrl(), p.minPrice(),
                            p.currency(), null, null, null, null, null))
                    .orElse(new ContextView("PRODUCT", c.productId(), "Product", null, null, null, null, null,
                            null, null, null));
        }
        if (c.suborderId() != null) {
            return projections.suborder(c.suborderId())
                    .map(s -> new ContextView("ORDER", null, null, firstImage(s.items()), null, s.currency(),
                            s.orderId(), s.suborderId(), s.status(), itemsSummary(s.items()), s.total()))
                    .orElse(new ContextView("ORDER", null, null, null, null, null, null, c.suborderId(), null,
                            null, null));
        }
        return new ContextView("GENERAL", null, null, null, null, null, null, null, null, null, null);
    }

    /** "Aurora Studio Headphones ×1 and 2 more items" */
    public static String itemsSummary(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return "";
        }
        JsonNode first = items.get(0);
        String head = first.path("productName").asText("Item") + " ×" + first.path("quantity").asInt(1);
        int rest = items.size() - 1;
        return rest == 0 ? head : head + " and " + rest + " more item" + (rest == 1 ? "" : "s");
    }

    private String firstImage(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return null;
        }
        String productId = items.get(0).path("productId").asText(null);
        if (productId == null) {
            return null;
        }
        try {
            return projections.product(UUID.fromString(productId)).map(ProjectionRepository.ProductRef::imageUrl)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String previewText(String type, String body, boolean deleted) {
        if (deleted) {
            return "Message deleted";
        }
        String text = switch (MessageType.valueOf(type)) {
            case TEXT, SYSTEM -> body;
            case IMAGE -> body == null || body.isBlank() ? "Photo" : "Photo: " + body;
            // a card sent with a question previews the question: that is what the other side needs to see
            case PRODUCT_CARD -> body == null || body.isBlank() ? "Shared a product" : body;
            case ORDER_CARD -> body == null || body.isBlank() ? "Shared an order" : body;
        };
        if (text == null) {
            return "";
        }
        return text.length() > 140 ? text.substring(0, 139) + "…" : text;
    }
}
