package com.marketflow.catalog.domain;

import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private Product draft() {
        return Product.draft(UUID.randomUUID(), UUID.randomUUID(), "Trail Runner Shoe", "trail-runner-shoe",
                "Light", "Stride", Map.of(), 5);
    }

    @Test
    void cannotPublishWithoutActiveVariant() {
        Product p = draft();
        assertThatThrownBy(() -> p.publish(true)).isInstanceOf(ApiException.class)
                .hasMessageContaining("variant");
    }

    @Test
    void unapprovedVendorCannotPublish() {
        Product p = draft();
        p.addVariant("TR-42", "EU 42", Map.of("size", "42"), new BigDecimal("89.90"), null);
        assertThatThrownBy(() -> p.publish(false)).isInstanceOf(ApiException.class);
        p.publish(true);
        assertThat(p.getStatus()).isEqualTo(Product.Status.PUBLISHED);
        assertThat(p.getPublishedAt()).isNotNull();
    }

    @Test
    void minAndMaxPriceFollowActiveVariants() {
        Product p = draft();
        ProductVariant a = p.addVariant("A-1", "A", Map.of(), new BigDecimal("10.00"), null);
        p.addVariant("B-1", "B", Map.of(), new BigDecimal("25.50"), null);
        assertThat(p.getMinPrice()).isEqualByComparingTo("10.00");
        assertThat(p.getMaxPrice()).isEqualByComparingTo("25.50");
        p.updateVariant(a.getId(), "A", Map.of(), new BigDecimal("10.00"), null, false);
        assertThat(p.getMinPrice()).isEqualByComparingTo("25.50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "10.001", "1000000.00"})
    void rejectsInvalidPrices(String price) {
        Product p = draft();
        assertThatThrownBy(() -> p.addVariant("X-1", "X", Map.of(), new BigDecimal(price), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void compareAtPriceMustExceedPrice() {
        Product p = draft();
        assertThatThrownBy(() -> p.addVariant("X-1", "X", Map.of(), new BigDecimal("10.00"),
                new BigDecimal("9.00"))).isInstanceOf(ApiException.class);
    }

    @Test
    void skusAreNormalisedAndUniqueWithinProduct() {
        Product p = draft();
        p.addVariant("tr-42", "42", Map.of(), new BigDecimal("1.00"), null);
        assertThat(p.getVariants().get(0).getSku()).isEqualTo("TR-42");
        assertThatThrownBy(() -> p.addVariant("TR-42", "dup", Map.of(), new BigDecimal("1.00"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> p.addVariant("bad sku!", "x", Map.of(), new BigDecimal("1.00"), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void priceChangeIsReported() {
        Product p = draft();
        ProductVariant v = p.addVariant("A-1", "A", Map.of(), new BigDecimal("10.00"), null);
        assertThat(p.updateVariant(v.getId(), "A", Map.of(), new BigDecimal("10.00"), null, true)).isEmpty();
        assertThat(p.updateVariant(v.getId(), "A", Map.of(), new BigDecimal("12.00"), null, true))
                .contains(new BigDecimal("10.00"));
    }

    @Test
    void blockedProductsAreFrozenAndOnlyNeverPublishedDraftsAreDeletable() {
        Product p = draft();
        assertThat(p.isDeletable()).isTrue();
        p.addVariant("A-1", "A", Map.of(), new BigDecimal("10.00"), null);
        p.publish(true);
        p.unpublish();
        assertThat(p.isDeletable()).isFalse();
        p.block("counterfeit");
        assertThatThrownBy(() -> p.publish(true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> p.addImage("https://x/y.png", null)).isInstanceOf(ApiException.class);
    }
}
