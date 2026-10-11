package com.marketflow.catalog.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storefront search on PostgreSQL full-text search (GIN index on a generated tsvector column).
 *
 * <p>All user input is bound as a parameter. The only dynamic SQL fragments are ORDER BY clauses chosen from a
 * fixed whitelist, so the query cannot be manipulated by request values (SQL-injection safe).
 */
@Repository
public class ProductSearchRepository {

    public enum Sort { RELEVANCE, NEWEST, PRICE_ASC, PRICE_DESC, RATING }

    private static final Map<Sort, String> ORDER_BY = Map.of(
            Sort.RELEVANCE, "rank DESC, p.published_at DESC, p.id",
            Sort.NEWEST, "p.published_at DESC, p.id",
            Sort.PRICE_ASC, "p.min_price ASC, p.id",
            Sort.PRICE_DESC, "p.min_price DESC, p.id",
            Sort.RATING, "p.rating_avg DESC, p.rating_count DESC, p.id");

    private final NamedParameterJdbcTemplate jdbc;

    public ProductSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Criteria(String q, UUID categoryId, UUID vendorId, BigDecimal minPrice, BigDecimal maxPrice,
                           BigDecimal minRating, Boolean inStock, Sort sort, int page, int size) {
    }

    public record Card(UUID id, String name, String slug, UUID vendorId, String vendorName, UUID categoryId,
                       BigDecimal minPrice, BigDecimal maxPrice, String currency, BigDecimal ratingAvg,
                       int ratingCount, boolean inStock, String imageUrl, Instant publishedAt) {
    }

    public record Result(List<Card> items, long total) {
    }

    public Result search(Criteria c) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE p.status = 'PUBLISHED' AND v.status = 'APPROVED' ");
        String rank = "0::real";
        if (c.q() != null && !c.q().isBlank()) {
            params.addValue("q", c.q().trim());
            where.append(" AND p.search_vector @@ websearch_to_tsquery('english', :q) ");
            rank = "ts_rank(p.search_vector, websearch_to_tsquery('english', :q))";
        }
        if (c.categoryId() != null) {
            params.addValue("categoryId", c.categoryId());
            where.append("""
                     AND p.category_id IN (
                         WITH RECURSIVE tree AS (
                             SELECT id FROM category WHERE id = :categoryId
                             UNION ALL SELECT ch.id FROM category ch JOIN tree t ON ch.parent_id = t.id)
                         SELECT id FROM tree)
                    """);
        }
        if (c.vendorId() != null) {
            params.addValue("vendorId", c.vendorId());
            where.append(" AND p.vendor_id = :vendorId ");
        }
        if (c.minPrice() != null) {
            params.addValue("minPrice", c.minPrice());
            where.append(" AND p.max_price >= :minPrice ");
        }
        if (c.maxPrice() != null) {
            params.addValue("maxPrice", c.maxPrice());
            where.append(" AND p.min_price <= :maxPrice ");
        }
        if (c.minRating() != null) {
            params.addValue("minRating", c.minRating());
            where.append(" AND p.rating_avg >= :minRating ");
        }
        if (Boolean.TRUE.equals(c.inStock())) {
            where.append(" AND p.in_stock ");
        }
        Sort sort = c.sort() == null ? Sort.NEWEST : c.sort();
        if (sort == Sort.RELEVANCE && (c.q() == null || c.q().isBlank())) {
            sort = Sort.NEWEST;
        }
        params.addValue("limit", c.size());
        params.addValue("offset", (long) c.page() * c.size());

        String from = " FROM product p JOIN vendor_projection v ON v.vendor_id = p.vendor_id ";
        String sql = "SELECT p.id, p.name, p.slug, p.vendor_id, v.business_name, p.category_id, p.min_price, "
                + "p.max_price, p.currency, p.rating_avg, p.rating_count, p.in_stock, p.published_at, "
                + "(SELECT i.url FROM product_image i WHERE i.product_id = p.id ORDER BY i.sort_order LIMIT 1) AS image_url, "
                + rank + " AS rank" + from + where + " ORDER BY " + ORDER_BY.get(sort)
                + " LIMIT :limit OFFSET :offset";

        List<Card> items = jdbc.query(sql, params, (rs, i) -> new Card(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getObject("vendor_id", UUID.class), rs.getString("business_name"),
                rs.getObject("category_id", UUID.class), rs.getBigDecimal("min_price"),
                rs.getBigDecimal("max_price"), rs.getString("currency"), rs.getBigDecimal("rating_avg"),
                rs.getInt("rating_count"), rs.getBoolean("in_stock"), rs.getString("image_url"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()));
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, params, Long.class);
        return new Result(items, total == null ? 0 : total);
    }
}
