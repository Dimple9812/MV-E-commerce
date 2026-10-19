package com.marketflow.chat.domain;

import com.marketflow.platform.security.Role;
import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAccessTest {

    final UUID buyer = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();
    final UUID vendorId = UUID.randomUUID();
    final VendorRef approved = new VendorRef(vendorId, owner, "Aurora", "aurora", "APPROVED");
    final VendorRef suspended = new VendorRef(vendorId, owner, "Aurora", "aurora", "SUSPENDED");

    Conversation conversation(ConversationStatus status) {
        return new Conversation(UUID.randomUUID(), buyer, "Ada M.", vendorId, null, null, status, Instant.now(), null,
                Instant.now());
    }

    static ChatActor actor(UUID id, Role... roles) {
        return new ChatActor(id, Set.of(roles), "Some Person");
    }

    @Test
    void buyerReadsAndWrites() {
        Access a = ChatAccess.write(actor(buyer, Role.CUSTOMER), conversation(ConversationStatus.OPEN), approved);
        assertThat(a.side()).isEqualTo(Side.BUYER);
        assertThat(a.canWrite()).isTrue();
    }

    @Test
    void vendorOwnerReadsAndWritesWhileApproved() {
        Access a = ChatAccess.write(actor(owner, Role.CUSTOMER, Role.VENDOR), conversation(ConversationStatus.OPEN),
                approved);
        assertThat(a.side()).isEqualTo(Side.VENDOR);
    }

    @Test
    void suspendedShopCanReadButNotWrite() {
        Conversation c = conversation(ConversationStatus.OPEN);
        ChatActor seller = actor(owner, Role.VENDOR);
        assertThat(ChatAccess.read(seller, c, suspended).canWrite()).isFalse();
        assertThatThrownBy(() -> ChatAccess.write(seller, c, suspended)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("forbidden");
        // the buyer can still write to a suspended shop's conversation (e.g. to ask about a refund)
        assertThat(ChatAccess.write(actor(buyer, Role.CUSTOMER), c, suspended).canWrite()).isTrue();
    }

    @Test
    void anotherVendorWithTheVendorRoleGets404() {
        ChatActor otherSeller = actor(UUID.randomUUID(), Role.CUSTOMER, Role.VENDOR);
        assertThat(ChatAccess.resolve(otherSeller, conversation(ConversationStatus.OPEN), approved)).isEmpty();
        assertThatThrownBy(() -> ChatAccess.read(otherSeller, conversation(ConversationStatus.OPEN), approved))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("not_found");
    }

    @Test
    void vendorProjectionOfADifferentShopGrantsNothing() {
        VendorRef otherShop = new VendorRef(UUID.randomUUID(), owner, "Other", "other", "APPROVED");
        assertThat(ChatAccess.resolve(actor(owner, Role.VENDOR), conversation(ConversationStatus.OPEN), otherShop))
                .isEmpty();
    }

    @Test
    void strangerGets404() {
        assertThat(ChatAccess.resolve(actor(UUID.randomUUID(), Role.CUSTOMER), conversation(ConversationStatus.OPEN),
                approved)).isEmpty();
    }

    @Test
    void adminReadsButNeverWrites() {
        ChatActor admin = actor(UUID.randomUUID(), Role.CUSTOMER, Role.ADMIN);
        Conversation c = conversation(ConversationStatus.OPEN);
        Access a = ChatAccess.read(admin, c, approved);
        assertThat(a.participant()).isFalse();
        assertThat(a.canWrite()).isFalse();
        assertThatThrownBy(() -> ChatAccess.write(admin, c, approved)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("forbidden");
    }

    @Test
    void blockedConversationIsReadOnlyForBothSides() {
        Conversation c = conversation(ConversationStatus.BLOCKED);
        assertThat(ChatAccess.read(actor(buyer, Role.CUSTOMER), c, approved).canWrite()).isFalse();
        assertThatThrownBy(() -> ChatAccess.write(actor(buyer, Role.CUSTOMER), c, approved))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("conversation_closed");
        assertThatThrownBy(() -> ChatAccess.write(actor(owner, Role.VENDOR), c, approved))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("conversation_closed");
    }

    @Test
    void nobodyMessagesTheirOwnShopAndInactiveShopsCannotBeContacted() {
        assertThatThrownBy(() -> ChatAccess.requireCanStart(actor(owner, Role.VENDOR), approved))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("own_shop");
        assertThatThrownBy(() -> ChatAccess.requireCanStart(actor(buyer, Role.CUSTOMER), suspended))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("not_found");
        assertThatThrownBy(() -> ChatAccess.requireCanStart(actor(buyer, Role.CUSTOMER), null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("not_found");
        ChatAccess.requireCanStart(actor(buyer, Role.CUSTOMER), approved);
    }

    @Test
    void displayNameIsShortened() {
        assertThat(new ChatActor(buyer, Set.of(), "Ada Lovelace Moreno").displayName()).isEqualTo("Ada M.");
        assertThat(new ChatActor(buyer, Set.of(), "Linus").displayName()).isEqualTo("Linus");
        assertThat(new ChatActor(buyer, Set.of(), null).displayName()).isEqualTo("Customer");
    }
}
