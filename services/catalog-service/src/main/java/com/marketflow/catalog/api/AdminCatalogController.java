package com.marketflow.catalog.api;

import com.marketflow.catalog.api.CatalogDtos.CategoryRequest;
import com.marketflow.catalog.api.CatalogDtos.CategoryView;
import com.marketflow.catalog.api.CatalogDtos.ModerationRequest;
import com.marketflow.catalog.api.CatalogDtos.ProductSummaryView;
import com.marketflow.catalog.api.CatalogDtos.ProductView;
import com.marketflow.catalog.application.ProductService;
import com.marketflow.catalog.application.StorefrontService;
import com.marketflow.catalog.domain.Product;
import com.marketflow.catalog.persistence.ProductRepository;
import com.marketflow.catalog.persistence.VendorProjectionRepository;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - catalog")
public class AdminCatalogController {

    private final ProductService products;
    private final ProductRepository productRepository;
    private final VendorProjectionRepository vendors;
    private final StorefrontService storefront;

    public AdminCatalogController(ProductService products, ProductRepository productRepository,
                                  VendorProjectionRepository vendors, StorefrontService storefront) {
        this.products = products;
        this.productRepository = productRepository;
        this.vendors = vendors;
        this.storefront = storefront;
    }

    @GetMapping("/api/v1/admin/products")
    @Transactional(readOnly = true)
    public PageView<ProductSummaryView> list(@RequestParam(required = false) Product.Status status,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
        var result = status == null ? productRepository.findAll(pageable)
                : productRepository.findByStatusOrderByUpdatedAtDesc(status, pageable);
        return PageView.of(result, ProductSummaryView::of);
    }

    @GetMapping("/api/v1/admin/products/{productId}")
    @Transactional(readOnly = true)
    public ProductView get(@PathVariable UUID productId) {
        Product p = productRepository.findDetailed(productId).orElseThrow(() -> ApiException.notFound("Product"));
        return ProductView.of(p, vendors.findById(p.getVendorId()).orElse(null));
    }

    @PostMapping("/api/v1/admin/products/{productId}/block")
    public ProductView block(@PathVariable UUID productId, @Valid @RequestBody ModerationRequest req) {
        return products.block(AuthenticatedUser.current().userId(), productId, req.note());
    }

    @PostMapping("/api/v1/admin/products/{productId}/unblock")
    public ProductView unblock(@PathVariable UUID productId) {
        return products.unblock(AuthenticatedUser.current().userId(), productId);
    }

    @PostMapping("/api/v1/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView createCategory(@Valid @RequestBody CategoryRequest req) {
        return storefront.createCategory(req.parentId(), req.name(), req.slug(), req.description(), req.sortOrder());
    }

    @PutMapping("/api/v1/admin/categories/{categoryId}")
    public CategoryView updateCategory(@PathVariable UUID categoryId, @Valid @RequestBody CategoryRequest req) {
        return storefront.updateCategory(categoryId, req.parentId(), req.name(), req.slug(), req.description(),
                req.sortOrder());
    }

    @DeleteMapping("/api/v1/admin/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID categoryId) {
        storefront.deleteCategory(categoryId);
    }
}
