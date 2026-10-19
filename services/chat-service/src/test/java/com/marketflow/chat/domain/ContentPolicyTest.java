package com.marketflow.chat.domain;

import com.marketflow.chat.domain.ContentPolicy.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentPolicyTest {

    final ContentPolicy policy = new ContentPolicy(2000, List.of("paypal.me", "wa.me", "venmo.com", "t.me"));

    @ParameterizedTest
    @ValueSource(strings = {"Call me on +91 98765 43210", "my number is 415-555-0199", "(206) 555 0123 after 6",
            "whatsapp 9876543210", "+1.415.555.0199"})
    void phoneNumbersAreRefused(String text) {
        assertThat(policy.check(text).violations()).contains(Violation.PHONE_NUMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"write to ada@example.com", "ada.moreno+shop@mail.example.co.uk please",
            "ada (at) example (dot) com", "ada [at] example.com", "ada at example dot com"})
    void emailAddressesAreRefusedIncludingSpelledOutOnes(String text) {
        assertThat(policy.check(text).violations()).contains(Violation.EMAIL_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay me at paypal.me/ada", "https://www.paypal.me/ada", "message me wa.me/919876543210",
            "send to ada@okhdfcbank", "UPI: shop.owner@ybl", "upi://pay?pa=x@y", "venmo.com/u/ada",
            "join t.me/cheapdeals"})
    void paymentAndMessagingLinksAreRefused(String text) {
        assertThat(policy.check(text).violations()).contains(Violation.PAYMENT_LINK);
    }

    @Test
    void upiIdIsAPaymentLinkNotAnEmail() {
        assertThat(policy.check("pay to name@ybl").violations()).containsExactly(Violation.PAYMENT_LINK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Is the 2-pack still available?", "It costs 1,299.00 with shipping",
            "Delivered on 2026-10-19, thanks!", "Can you ship 3 units by Friday?", "I'll be at home after 6 pm.",
            "Meet at noon.Thanks", "Size 10 or 11?", "Order 4 of the 250 ml bottles",
            "See https://marketflow.dev/products/abc for details", "The model is AX-500, colour #333"})
    void ordinaryMessagesPass(String text) {
        assertThat(policy.check(text).allowed()).as(text).isTrue();
    }

    @Test
    void textIsTrimmedAndLimited() {
        assertThat(policy.normaliseText("  hello \r\n there  ", true)).isEqualTo("hello \n there");
        assertThatThrownBy(() -> policy.normaliseText("   ", true)).hasMessageContaining("Write a message");
        assertThat(policy.normaliseText("  ", false)).isNull();
        assertThatThrownBy(() -> policy.normaliseText("x".repeat(2001), true)).hasMessageContaining("2000");
        assertThat(policy.normaliseText("x".repeat(2000), true)).hasSize(2000);
        // characters outside the BMP count once, not twice
        assertThat(policy.normaliseText("😀".repeat(2000), true)).isNotNull();
    }

    @Test
    void controlCharactersAreRejectedButMarkupIsKeptAsPlainText() {
        assertThatThrownBy(() -> policy.normaliseText("bell\u0007", true)).isInstanceOf(IllegalArgumentException.class);
        // markup is stored and delivered verbatim; clients render it as text, so it can never execute
        String html = "<img src=x onerror=alert(1)> <b>hi</b>";
        assertThat(policy.normaliseText(html, true)).isEqualTo(html);
        assertThat(policy.check(html).allowed()).isTrue();
    }
}
