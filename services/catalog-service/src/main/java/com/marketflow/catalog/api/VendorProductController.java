package com.marketflow.catalog.api;

import com.marketflow.catalog.api.CatalogDtos.ImageUrlRequest;
import com.marketflow.catalog.api.CatalogDtos.ProductRequest;
import com.marketflow.catalog.api.CatalogDtos.ProductSummaryView;
import com.marketflow.catalog.api.CatalogDtos.ProductUpdateRequest;
import com.marketflow.catalog.api.CatalogDtos.ProductView;
import com.marketflow.catalog.api.CatalogDtos.VariantRequest;
import com.marketflow.catalog.api.CatalogDtos.VariantUpdateRequest;
import com.marketflow.catalog.application.ProductService;
import com.marketflow.catalog.domain.Product;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vendor/products")
@PreAuthorize("hasRole('VENDOR')")
@Validated
@Tag(name = "Vendor - products")
public class VendorProductController {

    private final ProductService products;

    public VendorProductController(ProductService products) {
        this.products = products;
    }

    private UUID me() {
        return AuthenticatedUser.current().userId();
    }

    @GetMapping
    public PageView<ProductSummaryView> list(@RequestParam(required = false) Product.Status status,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageView.of(products.list(me(), status, PageRequest.of(page, size)), x -> x);
    }

    @GetMapping("/{productId}")
    public ProductView get(@PathVariable UUID productId) {
        return products.get(me(), productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody ProductRequest req) {
        return products.create(me(), req);
    }

    @PatchMapping("/{productId}")
    public ProductView update(@PathVariable UUID productId, @Valid @RequestBody ProductUpdateRequest req) {
        return products.update(me(), productId, req);
    }

    @PostMapping("/{productId}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView addVariant(@PathVariable UUID productId, @Valid @RequestBody VariantRequest req) {
        return products.addVariant(me(), productId, req);
    }

    @PatchMapping("/{productId}/variants/{variantId}")
    public ProductView updateVariant(@PathVariable UUID productId, @PathVariable UUID variantId,
                                     @Valid @RequestBody VariantUpdateRequest req) {
        return products.updateVariant(me(), productId, variantId, req);
    }

    @PostMapping("/{productId}/publish")
    public ProductView publish(@PathVariable UUID productId) {
        return products.publish(me(), productId);
    }

    @PostMapping("/{productId}/unpublish")
    public ProductView unpublish(@PathVariable UUID productId) {
        return products.unpublish(me(), productId);
    }

    @PostMapping("/{productId}/archive")
    public ProductView archive(@PathVariable UUID productId) {
        return products.archive(me(), productId);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(@PathVariable UUID productId) {
        boolean deleted = products.delete(me(), productId);
        // 204 when removed, 202 when it had to be archived instead (it was visible to customers before)
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.accepted().build();
    }

    @PostMapping("/{productId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView addImageUrl(@PathVariable UUID productId, @Valid @RequestBody ImageUrlRequest req) {
        return products.addImageUrl(me(), productId, req.url(), req.altText());
    }

    @PostMapping(value = "/{productId}/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView upload(@PathVariable UUID productId, @RequestPart("file") MultipartFile file,
                              @RequestParam(required = false) String altText) {
        try {
            return products.uploadImage(me(), productId, file.getBytes(),
                    altText == null ? null : altText.substring(0, Math.min(altText.length(), 200)));
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_image", "Could not read the uploaded file");
        }
    }

    @DeleteMapping("/{productId}/images/{imageId}")
    public ProductView removeImage(@PathVariable UUID productId, @PathVariable UUID imageId) {
        return products.removeImage(me(), productId, imageId);
    }
}
