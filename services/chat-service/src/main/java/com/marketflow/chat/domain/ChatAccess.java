package com.marketflow.chat.domain;

import com.marketflow.platform.web.ApiException;

import java.util.Optional;

/**
 * Authorisation rules for conversations. Pure functions over the conversation, the vendor projection and the
 * actor, so they are unit-tested exhaustively (ChatAccessTest).
 *
 * <ul>
 *   <li>The buyer of a conversation reads and writes it.</li>
 *   <li>The vendor's owner (the only seller account of a vendor today) reads it, and writes while the vendor is
 *       APPROVED; a suspended shop can read its history but not answer.</li>
 *   <li>An administrator reads any conversation (disputes) and never writes.</li>
 *   <li>Everyone else gets 404, so conversation ids cannot be probed.</li>
 *   <li>A BLOCKED conversation is read-only for everyone.</li>
 * </ul>
 */
public final class ChatAccess {

    private ChatAccess() {
    }

    public static Optional<Access> resolve(ChatActor actor, Conversation c, VendorRef vendor) {
        if (actor.userId().equals(c.buyerId())) {
            return Optional.of(new Access(Side.BUYER, c.writable()));
        }
        if (vendor != null && vendor.vendorId().equals(c.vendorId()) && actor.userId().equals(vendor.ownerUserId())) {
            return Optional.of(new Access(Side.VENDOR, c.writable() && vendor.approved()));
        }
        if (actor.admin()) {
            return Optional.of(Access.ADMIN_READ_ONLY);
        }
        return Optional.empty();
    }

    public static Access read(ChatActor actor, Conversation c, VendorRef vendor) {
        return resolve(actor, c, vendor).orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    /** Participants only; administrators get 403 (they can see the conversation, so 404 would be misleading). */
    public static Access write(ChatActor actor, Conversation c, VendorRef vendor) {
        Access a = read(actor, c, vendor);
        if (!a.participant()) {
            throw ApiException.forbidden("Administrators can read conversations but not take part in them");
        }
        if (!a.canWrite()) {
            if (!c.writable()) {
                throw ApiException.conflict("conversation_closed", "This conversation has been closed");
            }
            throw ApiException.forbidden("This shop cannot send messages while it is not active");
        }
        return a;
    }

    /** Opening a conversation with a shop: the shop must be active, and nobody can message their own shop. */
    public static void requireCanStart(ChatActor actor, VendorRef vendor) {
        if (vendor == null || !vendor.approved()) {
            throw ApiException.notFound("Shop");
        }
        if (actor.userId().equals(vendor.ownerUserId())) {
            throw ApiException.unprocessable("own_shop", "You cannot start a conversation with your own shop");
        }
    }
}
