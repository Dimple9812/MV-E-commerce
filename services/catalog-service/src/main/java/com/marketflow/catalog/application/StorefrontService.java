package com.marketflow.catalog.application;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.catalog.api.CatalogDtos.CategoryView;
import com.marketflow.catalog.api.CatalogDtos.ProductView;
import com.marketflow.catalog.cache.CacheAside;
import com.marketflow.catalog.cache.CacheKeys;
import com.marketflow.catalog.domain.Category;
import com.marketflow.catalog.domain.Product;
import com.marketflow.catalog.persistence.CategoryRepository;
import com.marketflow.catalog.persistence.ProductRepository;
import com.marketflow.catalog.persistence.ProductSearchRepository;
import com.marketflow.catalog.persistence.VendorProjectionRepository;
import com.marketflow.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public, read-only storefront queries. Product details and the category tree are cached (cache-aside);
 * search results are not, because the key space (query × filters × page) is unbounded and results must reflect
 * moderation/vendor-status changes promptly.
 */
@Service
public class StorefrontService {

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);
    private static final Duration CATEGORY_TTL = Duration.ofHours(1);

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final VendorProjectionRepository vendors;
    private final ProductSearchRepository search;
    private final CacheAside cache;
    private final TransactionTemplate readTx;
    private final JavaType categoryListType;

    public StorefrontService(ProductRepository products, CategoryRepository categories,
                             VendorProjectionRepository vendors, ProductSearchRepository search, CacheAside cache,
                             TransactionTemplate tx, ObjectMapper mapper) {
        this.products = products;
        this.categories = categories;
        this.vendors = vendors;
        this.search = search;
        this.cache = cache;
        this.readTx = new TransactionTemplate(tx.getTransactionManager());
        this.readTx.setReadOnly(true);
        this.categoryListType = mapper.getTypeFactory().constructCollectionType(List.class, CategoryView.class);
    }

    public ProductSearchRepository.Result search(ProductSearchRepository.Criteria criteria) {
        return search.search(criteria);
    }

    /** Only PUBLISHED products of APPROVED vendors are visible; everything else is a 404 for the public. */
    public ProductView product(UUID id) {
        return cache.get(CacheKeys.product(id), PRODUCT_TTL, ProductView.class, () -> loadPublic(id))
                .orElseThrow(() -> ApiException.notFound("Product"));
    }

    private Optional<ProductView> loadPublic(UUID id) {
        return readTx.execute(status -> products.findDetailed(id)
                .filter(p -> p.getStatus() == Product.Status.PUBLISHED)
                .flatMap(p -> vendors.findById(p.getVendorId()).filter(v -> v.isApproved())
                        .map(v -> ProductView.of(p, v))));
    }

    public List<CategoryView> categories() {
        Optional<List<CategoryView>> list = cache.get(CacheKeys.CATEGORIES, CATEGORY_TTL, categoryListType,
                () -> Optional.of(categories.findAllByOrderBySortOrderAscNameAsc().stream()
                        .map(CategoryView::of).toList()));
        return list.orElse(List.of());
    }

    public CategoryView categoryBySlug(String slug) {
        return categories().stream().filter(c -> c.slug().equals(slug)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Category"));
    }

    // ---------- admin category management ----------

    public CategoryView createCategory(UUID parentId, String name, String slug, String description, int sortOrder) {
        return readWrite(() -> {
            if (categories.existsBySlug(slug)) {
                throw ApiException.conflict("duplicate_slug", "Category slug already exists");
            }
            if (parentId != null && !categories.existsById(parentId)) {
                throw ApiException.badRequest("invalid_parent", "Unknown parent category");
            }
            Category c = categories.save(new Category(parentId, name, slug, description, sortOrder));
            cache.evictAfterCommit(CacheKeys.CATEGORIES);
            return CategoryView.of(c);
        });
    }

    public CategoryView updateCategory(UUID id, UUID parentId, String name, String slug, String description,
                                       int sortOrder) {
        return readWrite(() -> {
            Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
            if (id.equals(parentId)) {
                throw ApiException.badRequest("invalid_parent", "A category cannot be its own parent");
            }
            categories.findBySlug(slug).filter(other -> !other.getId().equals(id)).ifPresent(o -> {
                throw ApiException.conflict("duplicate_slug", "Category slug already exists");
            });
            c.update(parentId, name, slug, description, sortOrder);
            cache.evictAfterCommit(CacheKeys.CATEGORIES);
            return CategoryView.of(c);
        });
    }

    public void deleteCategory(UUID id) {
        readWrite(() -> {
            if (categories.existsByParentId(id) || categories.hasProducts(id)) {
                throw ApiException.conflict("category_in_use", "Category has subcategories or products");
            }
            categories.deleteById(id);
            cache.evictAfterCommit(CacheKeys.CATEGORIES);
            return null;
        });
    }

    private <T> T readWrite(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(readTx.getTransactionManager()).execute(s -> work.get());
    }
}
