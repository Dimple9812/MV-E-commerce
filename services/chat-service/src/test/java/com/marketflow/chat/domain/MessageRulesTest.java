package com.marketflow.chat.domain;

import com.marketflow.chat.application.ConversationService.Cursor;
import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRulesTest {

    final UUID sender = UUID.randomUUID();
    final Instant sent = Instant.parse("2026-10-19T10:00:00Z");
    final Duration window = Duration.ofMinutes(10);

    Message message(MessageType type, Instant deletedAt) {
        return new Message(1, UUID.randomUUID(), sender, "BUYER", type, "hi", null, null, UUID.randomUUID(), sent,
                null, deletedAt);
    }

    @Test
    void senderCanChangeWithinTheWindowOnly() {
        Message m = message(MessageType.TEXT, null);
        assertThat(m.changeableBy(sender, sent.plus(Duration.ofMinutes(9)), window, true)).isTrue();
        assertThat(m.changeableBy(sender, sent.plus(window), window, true)).isTrue();
        assertThat(m.changeableBy(sender, sent.plus(window).plusSeconds(1), window, true)).isFalse();
    }

    @Test
    void othersCannotChangeAndDeletedMessagesAreFinal() {
        assertThat(message(MessageType.TEXT, null).changeableBy(UUID.randomUUID(), sent, window, false)).isFalse();
        assertThat(message(MessageType.TEXT, sent).changeableBy(sender, sent, window, false)).isFalse();
    }

    @Test
    void onlyTextCanBeEditedButCardsCanBeDeleted() {
        Message card = message(MessageType.PRODUCT_CARD, null);
        assertThat(card.changeableBy(sender, sent, window, true)).isFalse();
        assertThat(card.changeableBy(sender, sent, window, false)).isTrue();
    }

    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        Instant at = Instant.parse("2026-10-19T10:00:00.123456Z");
        UUID id = UUID.randomUUID();
        Cursor c = Cursor.decode(Cursor.encode(at, id));
        assertThat(c.at()).isEqualTo(at);
        assertThat(c.id()).isEqualTo(id);
        assertThat(Cursor.decode(null)).isNull();
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor")).isInstanceOf(ApiException.class);
    }
}
