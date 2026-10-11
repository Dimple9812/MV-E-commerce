package com.marketflow.catalog.api;

import com.marketflow.catalog.api.CatalogDtos.CategoryView;
import com.marketflow.catalog.api.CatalogDtos.ProductView;
import com.marketflow.catalog.application.ImageStorage;
import com.marketflow.catalog.application.StorefrontService;
import com.marketflow.catalog.persistence.ProductSearchRepository;
import com.marketflow.catalog.persistence.ProductSearchRepository.Card;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Storefront")
public class StorefrontController {

    private final StorefrontService storefront;
    private final ImageStorage images;

    public StorefrontController(StorefrontService storefront, ImageStorage images) {
        this.storefront = storefront;
        this.images = images;
    }

    @GetMapping("/api/v1/products")
    @Operation(summary = "Search and browse published products",
            description = "Full-text search (q) with filters. sort: relevance|newest|price_asc|price_desc|rating")
    public PageView<Card> search(@RequestParam(required = false) @Size(max = 200) String q,
                                 @RequestParam(required = false) UUID categoryId,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) UUID vendorId,
                                 @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
                                 @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
                                 @RequestParam(required = false) @DecimalMin("0") @DecimalMax("5") BigDecimal minRating,
                                 @RequestParam(required = false) Boolean inStock,
                                 @RequestParam(defaultValue = "newest") String sort,
                                 @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page,
                                 @RequestParam(defaultValue = "24") @Min(1) @Max(60) int size) {
        UUID cat = categoryId != null ? categoryId : category == null ? null : storefront.categoryBySlug(category).id();
        ProductSearchRepository.Sort s;
        try {
            s = ProductSearchRepository.Sort.valueOf(sort.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            s = ProductSearchRepository.Sort.NEWEST;
        }
        ProductSearchRepository.Result r = storefront.search(new ProductSearchRepository.Criteria(q, cat, vendorId,
                minPrice, maxPrice, minRating, inStock, s, page, size));
        return PageView.of(r.items(), page, size, r.total());
    }

    @GetMapping("/api/v1/vendors/{vendorId}/products")
    @Operation(summary = "Vendor storefront listing")
    public PageView<Card> vendorProducts(@PathVariable UUID vendorId,
                                         @RequestParam(defaultValue = "newest") String sort,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "24") @Min(1) @Max(60) int size) {
        return search(null, null, null, vendorId, null, null, null, null, sort, page, size);
    }

    @GetMapping("/api/v1/products/{productId}")
    public ResponseEntity<ProductView> product(@PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(storefront.product(productId));
    }

    @GetMapping("/api/v1/categories")
    public List<CategoryView> categories() {
        return storefront.categories();
    }

    @GetMapping("/api/v1/products/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String name) {
        return images.load(name)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(img.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'")
                        .body(img.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }
}
