package com.marketflow.review.application;

import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    public static final String TOPIC = "review.events";
    static final Duration EDIT_WINDOW = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;

    public ReviewService(JdbcTemplate jdbc, OutboxWriter outbox, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record Review(UUID id, UUID productId, UUID customerId, String authorName, int rating, String title,
                         String body, String status, boolean verifiedPurchase, String moderationNote,
                         Instant createdAt, Instant updatedAt) {
    }

    public record Rating(UUID productId, int reviewCount, BigDecimal averageRating, Map<String, Integer> histogram) {
    }

    public record ReviewEvent(UUID reviewId, UUID productId, UUID customerId, int rating, String status) {
    }

    public record RatingEvent(UUID productId, BigDecimal averageRating, int reviewCount) {
    }

    static final RowMapper<Review> MAPPER = (rs, i) -> new Review(rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class), rs.getObject("customer_id", UUID.class),
            rs.getString("author_name"), rs.getInt("rating"), rs.getString("title"), rs.getString("body"),
            rs.getString("status"), rs.getBoolean("verified_purchase"), rs.getString("moderation_note"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    // ---------- eligibility ----------

    public record Eligibility(boolean eligible, String reason) {
    }

    @Transactional(readOnly = true)
    public Eligibility eligibility(UUID customerId, UUID productId) {
        if (!hasPurchased(customerId, productId)) {
            return new Eligibility(false, "NOT_PURCHASED");
        }
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM review WHERE product_id = ? AND customer_id = ? "
                + "AND status <> 'DELETED'", Integer.class, productId, customerId);
        return existing != null && existing > 0 ? new Eligibility(false, "ALREADY_REVIEWED")
                : new Eligibility(true, null);
    }

    private boolean hasPurchased(UUID customerId, UUID productId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM purchase WHERE customer_id = ? AND product_id = ? "
                + "AND NOT cancelled", Integer.class, customerId, productId);
        return n != null && n > 0;
    }

    // ---------- commands ----------

    /** Only customers with a confirmed, non-cancelled purchase of the product may review it. */
    @Transactional
    public Review create(UUID customerId, String authorName, UUID productId, int rating, String title, String body) {
        if (!hasPurchased(customerId, productId)) {
            throw ApiException.forbidden("Only customers who bought this product can review it");
        }
        UUID id = UUID.randomUUID();
        // a previously deleted review may be replaced; otherwise the unique constraint blocks duplicates
        jdbc.update("DELETE FROM review WHERE product_id = ? AND customer_id = ? AND status = 'DELETED'", productId,
                customerId);
        try {
            jdbc.update("""
                    INSERT INTO review (id, product_id, customer_id, author_name, rating, title, body, status,
                                        verified_purchase)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PUBLISHED', true)
                    """, id, productId, customerId, authorName, rating, title.trim(), body.trim());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("already_reviewed", "You have already reviewed this product");
        }
        outbox.append(TOPIC, "ReviewCreated", 1, "Review", productId.toString(),
                new ReviewEvent(id, productId, customerId, rating, "PUBLISHED"));
        recompute(productId);
        return get(id);
    }

    @Transactional
    public Review update(UUID customerId, UUID reviewId, int rating, String title, String body) {
        Review r = owned(customerId, reviewId);
        if (r.createdAt().plus(EDIT_WINDOW).isBefore(Instant.now())) {
            throw ApiException.conflict("edit_window_closed", "Reviews can be edited for 30 days");
        }
        if (!r.status().equals("PUBLISHED")) {
            throw ApiException.conflict("not_editable", "This review cannot be edited");
        }
        jdbc.update("UPDATE review SET rating = ?, title = ?, body = ?, updated_at = now(), version = version + 1 "
                + "WHERE id = ?", rating, title.trim(), body.trim(), reviewId);
        outbox.append(TOPIC, "ReviewUpdated", 1, "Review", r.productId().toString(),
                new ReviewEvent(reviewId, r.productId(), customerId, rating, "PUBLISHED"));
        recompute(r.productId());
        return get(reviewId);
    }

    @Transactional
    public void delete(UUID customerId, UUID reviewId) {
        Review r = owned(customerId, reviewId);
        jdbc.update("UPDATE review SET status = 'DELETED', updated_at = now() WHERE id = ?", reviewId);
        outbox.append(TOPIC, "ReviewDeleted", 1, "Review", r.productId().toString(),
                new ReviewEvent(reviewId, r.productId(), customerId, r.rating(), "DELETED"));
        recompute(r.productId());
    }

    @Transactional
    public Review moderate(UUID adminId, UUID reviewId, boolean hide, String note) {
        Review r = get(reviewId);
        if (r.status().equals("DELETED")) {
            throw ApiException.conflict("deleted", "Deleted reviews cannot be moderated");
        }
        String status = hide ? "HIDDEN" : "PUBLISHED";
        jdbc.update("UPDATE review SET status = ?, moderation_note = ?, updated_at = now() WHERE id = ?", status,
                note, reviewId);
        outbox.append(TOPIC, hide ? "ReviewHidden" : "ReviewRestored", 1, "Review", r.productId().toString(),
                new ReviewEvent(reviewId, r.productId(), r.customerId(), r.rating(), status));
        audit.record(adminId, "ADMIN", hide ? "review.hidden" : "review.restored", "Review", reviewId.toString(),
                Map.of("note", note == null ? "" : note));
        recompute(r.productId());
        return get(reviewId);
    }

    // ---------- purchases (from order events) ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPurchases(UUID orderId, UUID customerId, Instant at, List<UUID> productIds) {
        for (UUID p : productIds) {
            jdbc.update("INSERT INTO purchase (customer_id, product_id, order_id, purchased_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", customerId, p, orderId, java.sql.Timestamp.from(at));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPurchases(UUID orderId) {
        jdbc.update("UPDATE purchase SET cancelled = true WHERE order_id = ?", orderId);
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public List<Review> published(UUID productId, int page, int size) {
        return jdbc.query("SELECT * FROM review WHERE product_id = ? AND status = 'PUBLISHED' "
                + "ORDER BY created_at DESC LIMIT ? OFFSET ?", MAPPER, productId, size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public List<Review> mine(UUID customerId) {
        return jdbc.query("SELECT * FROM review WHERE customer_id = ? AND status <> 'DELETED' ORDER BY created_at DESC",
                MAPPER, customerId);
    }

    @Transactional(readOnly = true)
    public List<Review> byStatus(String status, int limit) {
        return jdbc.query("SELECT * FROM review WHERE status = ? ORDER BY created_at DESC LIMIT ?", MAPPER, status,
                limit);
    }

    @Transactional(readOnly = true)
    public Rating rating(UUID productId) {
        List<Rating> r = jdbc.query("SELECT product_id, review_count, average, histogram::text h FROM product_rating "
                + "WHERE product_id = ?", (rs, i) -> new Rating(rs.getObject("product_id", UUID.class),
                rs.getInt("review_count"), rs.getBigDecimal("average"), parseHistogram(rs.getString("h"))), productId);
        return r.isEmpty() ? new Rating(productId, 0, BigDecimal.ZERO.setScale(2), Map.of()) : r.get(0);
    }

    private Review get(UUID id) {
        return jdbc.query("SELECT * FROM review WHERE id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Review"));
    }

    private Review owned(UUID customerId, UUID reviewId) {
        return jdbc.query("SELECT * FROM review WHERE id = ? AND customer_id = ? AND status <> 'DELETED'", MAPPER,
                reviewId, customerId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Review"));
    }

    /** Recomputes the aggregate from published reviews in the same transaction and publishes it for catalog. */
    private void recompute(UUID productId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT rating, count(*) c FROM review "
                + "WHERE product_id = ? AND status = 'PUBLISHED' GROUP BY rating", productId);
        int count = 0;
        int sum = 0;
        Map<String, Integer> histogram = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            histogram.put(String.valueOf(star), 0);
        }
        for (Map<String, Object> row : rows) {
            int rating = ((Number) row.get("rating")).intValue();
            int c = ((Number) row.get("c")).intValue();
            histogram.put(String.valueOf(rating), c);
            count += c;
            sum += rating * c;
        }
        BigDecimal avg = count == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
        jdbc.update("""
                INSERT INTO product_rating (product_id, review_count, average, histogram) VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (product_id) DO UPDATE SET review_count = EXCLUDED.review_count,
                    average = EXCLUDED.average, histogram = EXCLUDED.histogram, updated_at = now()
                """, productId, count, avg, toJson(histogram));
        outbox.append(TOPIC, "ProductRatingUpdated", 1, "ProductRating", productId.toString(),
                new RatingEvent(productId, avg, count));
    }

    private static String toJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return sb.append('}').toString();
    }

    private static Map<String, Integer> parseHistogram(String json) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (json == null) {
            return m;
        }
        for (String part : json.replaceAll("[{}\"\\s]", "").split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) {
                m.put(kv[0], Integer.parseInt(kv[1]));
            }
        }
        return m;
    }
}
