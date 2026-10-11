package com.marketflow.catalog.api;

import com.marketflow.catalog.domain.Category;
import com.marketflow.catalog.domain.Product;
import com.marketflow.catalog.domain.ProductImage;
import com.marketflow.catalog.domain.ProductVariant;
import com.marketflow.catalog.domain.VendorProjection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ---------- requests ----------

    public record VariantRequest(
            @NotBlank @Size(min = 2, max = 64) String sku,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 80) String> attributes,
            @NotNull @DecimalMin(value = "0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
            BigDecimal price,
            @DecimalMin(value = "0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
            BigDecimal compareAtPrice) {
    }

    public record VariantUpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 80) String> attributes,
            @NotNull @DecimalMin(value = "0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
            BigDecimal price,
            @DecimalMin(value = "0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
            BigDecimal compareAtPrice,
            boolean active) {
    }

    public record ProductRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(min = 3, max = 200) String name,
            @Size(max = 5000) String description,
            @Size(max = 100) String brand,
            @Size(max = 20) Map<@Size(max = 40) String, @Size(max = 200) String> attributes,
            @Min(1) @Max(100) Integer maxPerOrder,
            @Valid @Size(max = 50) List<VariantRequest> variants) {
    }

    public record ProductUpdateRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(min = 3, max = 200) String name,
            @Size(max = 5000) String description,
            @Size(max = 100) String brand,
            @Size(max = 20) Map<@Size(max = 40) String, @Size(max = 200) String> attributes,
            @Min(1) @Max(100) Integer maxPerOrder) {
    }

    public record ImageUrlRequest(
            @NotBlank @Size(max = 500) @Pattern(regexp = "^https://[^\\s]+$", message = "must be an https URL")
            String url,
            @Size(max = 200) String altText) {
    }

    public record ModerationRequest(@NotBlank @Size(max = 500) String note) {
    }

    public record CategoryRequest(UUID parentId, @NotBlank @Size(max = 80) String name,
                                  @NotBlank @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") @Size(max = 100) String slug,
                                  @Size(max = 500) String description, @Min(0) @Max(1000) int sortOrder) {
    }

    // ---------- views ----------

    public record VariantView(UUID id, String sku, String name, Map<String, String> attributes, BigDecimal price,
                              BigDecimal compareAtPrice, boolean active, int availableQty) {
        public static VariantView of(ProductVariant v) {
            return new VariantView(v.getId(), v.getSku(), v.getName(), v.getAttributes(), v.getPrice(),
                    v.getCompareAtPrice(), v.isActive(), v.getAvailableQty());
        }
    }

    public record ImageView(UUID id, String url, String altText) {
        public static ImageView of(ProductImage i) {
            return new ImageView(i.getId(), i.getUrl(), i.getAltText());
        }
    }

    public record VendorRef(UUID id, String name, String slug) {
        public static VendorRef of(VendorProjection v) {
            return v == null ? null : new VendorRef(v.getVendorId(), v.getBusinessName(), v.getSlug());
        }
    }

    public record ProductView(UUID id, VendorRef vendor, UUID categoryId, String name, String slug,
                              String description, String brand, Map<String, String> attributes, String currency,
                              String status, String moderationNote, BigDecimal minPrice, BigDecimal maxPrice,
                              boolean inStock, BigDecimal ratingAvg, int ratingCount, int maxPerOrder,
                              Instant publishedAt, Instant updatedAt, List<VariantView> variants,
                              List<ImageView> images) {
        public static ProductView of(Product p, VendorProjection vendor) {
            return new ProductView(p.getId(), VendorRef.of(vendor), p.getCategoryId(), p.getName(), p.getSlug(),
                    p.getDescription(), p.getBrand(), p.getAttributes(), p.getCurrency(), p.getStatus().name(),
                    p.getModerationNote(), p.getMinPrice(), p.getMaxPrice(), p.isInStock(), p.getRatingAvg(),
                    p.getRatingCount(), p.getMaxPerOrder(), p.getPublishedAt(), p.getUpdatedAt(),
                    p.getVariants().stream().map(VariantView::of).toList(),
                    p.getImages().stream().map(ImageView::of).toList());
        }
    }

    public record ProductSummaryView(UUID id, String name, String status, BigDecimal minPrice, BigDecimal maxPrice,
                                     boolean inStock, int variantCount, Instant updatedAt, String imageUrl) {
        public static ProductSummaryView of(Product p) {
            return new ProductSummaryView(p.getId(), p.getName(), p.getStatus().name(), p.getMinPrice(),
                    p.getMaxPrice(), p.isInStock(), p.getVariants().size(), p.getUpdatedAt(),
                    p.getImages().isEmpty() ? null : p.getImages().get(0).getUrl());
        }
    }

    public record CategoryView(UUID id, UUID parentId, String name, String slug, String description, int sortOrder) {
        public static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getParentId(), c.getName(), c.getSlug(), c.getDescription(),
                    c.getSortOrder());
        }
    }

    public record CategoryList(@NotEmpty List<CategoryView> items) {
    }
}
