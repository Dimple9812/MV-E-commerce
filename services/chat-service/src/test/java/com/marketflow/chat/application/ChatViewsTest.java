package com.marketflow.chat.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatViewsTest {

    @Test
    void inboxPreviewShowsTheTextOfACardWhenThereIsOne() {
        assertThat(ChatViews.previewText("PRODUCT_CARD", "Do these come in white?", false))
                .isEqualTo("Do these come in white?");
        assertThat(ChatViews.previewText("PRODUCT_CARD", null, false)).isEqualTo("Shared a product");
        assertThat(ChatViews.previewText("ORDER_CARD", " ", false)).isEqualTo("Shared an order");
        assertThat(ChatViews.previewText("IMAGE", null, false)).isEqualTo("Photo");
        assertThat(ChatViews.previewText("IMAGE", "the scratch", false)).isEqualTo("Photo: the scratch");
    }

    @Test
    void inboxPreviewHidesDeletedMessagesAndCutsLongOnes() {
        assertThat(ChatViews.previewText("TEXT", "secret", true)).isEqualTo("Message deleted");
        String preview = ChatViews.previewText("TEXT", "x".repeat(500), false);
        assertThat(preview).hasSize(140).endsWith("…");
    }
}
