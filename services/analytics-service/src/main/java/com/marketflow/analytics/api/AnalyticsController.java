package com.marketflow.analytics.api;

import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard queries over the event-built read models. Responses carry {@code eventuallyConsistent=true} and the
 * timestamp of the newest processed event so the UI can show how fresh the numbers are.
 */
@RestController
@Validated
@Tag(name = "Analytics")
public class AnalyticsController {

    private final JdbcTemplate jdbc;

    public AnalyticsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/analytics/overview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Platform KPIs (eventually consistent)")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS orders,
                       count(*) FILTER (WHERE status NOT IN ('PENDING', 'CANCELLED', 'FAILED')) AS confirmed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       coalesce(sum(total) FILTER (WHERE confirmed_at IS NOT NULL AND status <> 'CANCELLED'), 0) AS gmv
                FROM order_fact WHERE created_at >= now() - (? * interval '1 day')
                """, days);
        long confirmed = ((Number) totals.get("confirmed")).longValue();
        BigDecimal gmv = (BigDecimal) totals.get("gmv");
        totals.put("averageOrderValue", confirmed == 0 ? BigDecimal.ZERO
                : gmv.divide(BigDecimal.valueOf(confirmed), 2, RoundingMode.HALF_EVEN));
        totals.put("refundTotal", jdbc.queryForObject("SELECT coalesce(sum(refunded_amount), 0) FROM payment_fact "
                + "WHERE updated_at >= now() - (? * interval '1 day')", BigDecimal.class, days));
        out.put("totals", totals);
        out.put("statusDistribution", jdbc.queryForList("SELECT status, count(*) AS count FROM order_fact "
                + "WHERE created_at >= now() - (? * interval '1 day') GROUP BY status ORDER BY count DESC", days));
        out.put("ordersOverTime", jdbc.queryForList("""
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day, count(o.order_id) AS orders,
                       coalesce(sum(o.total) FILTER (WHERE o.confirmed_at IS NOT NULL AND o.status <> 'CANCELLED'), 0) AS gmv
                FROM generate_series(date_trunc('day', now()) - ((? - 1) * interval '1 day'), date_trunc('day', now()),
                                     interval '1 day') AS d(day)
                LEFT JOIN order_fact o ON date_trunc('day', o.created_at) = d.day
                GROUP BY d.day ORDER BY d.day
                """, days));
        out.put("salesByVendor", jdbc.queryForList("""
                SELECT s.vendor_id, max(s.vendor_name) AS vendor_name, count(*) AS suborders,
                       sum(s.total) AS revenue, sum(s.commission) AS commission
                FROM suborder_fact s WHERE s.status <> 'CANCELLED' AND s.confirmed_at >= now() - (? * interval '1 day')
                GROUP BY s.vendor_id ORDER BY revenue DESC LIMIT 10
                """, days));
        out.put("bestSellers", bestSellers(null, days));
        out.put("payments", jdbc.queryForList("SELECT status, count(*) AS count, coalesce(sum(amount), 0) AS amount "
                + "FROM payment_fact WHERE updated_at >= now() - (? * interval '1 day') GROUP BY status", days));
        Map<String, Object> rates = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status IN ('CAPTURED', 'REFUNDED', 'AUTHORIZED')) AS succeeded,
                       count(*) FILTER (WHERE status IN ('DECLINED', 'CAPTURE_FAILED', 'VOIDED')) AS failed
                FROM payment_fact WHERE updated_at >= now() - (? * interval '1 day')
                """, days);
        long ok = ((Number) rates.get("succeeded")).longValue();
        long bad = ((Number) rates.get("failed")).longValue();
        rates.put("successRate", ok + bad == 0 ? null : BigDecimal.valueOf(ok * 100.0 / (ok + bad))
                .setScale(1, RoundingMode.HALF_EVEN));
        out.put("paymentRates", rates);
        out.put("inventoryAlerts", jdbc.queryForList("""
                SELECT a.variant_id, a.sku, a.product_name, a.available, a.threshold, a.detected_at, v.business_name
                FROM inventory_alert a LEFT JOIN vendor_dim v ON v.vendor_id = a.vendor_id
                WHERE a.resolved_at IS NULL ORDER BY a.available, a.detected_at DESC LIMIT 20
                """));
        out.put("operations", operations());
        freshness(out);
        return out;
    }

    @GetMapping("/api/v1/admin/analytics/dead-letters")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> deadLetters(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return jdbc.queryForList("SELECT topic, partition_no, offset_no, consumer, exception, event_type, event_id, "
                + "received_at FROM dead_letter ORDER BY received_at DESC LIMIT ?", limit);
    }

    @GetMapping("/api/v1/vendor/analytics/overview")
    @PreAuthorize("hasRole('VENDOR')")
    @Operation(summary = "Sales analytics for the calling vendor only")
    public Map<String, Object> vendorOverview(@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        UUID vendor = jdbc.queryForList("SELECT vendor_id FROM vendor_dim WHERE owner_user_id = ? AND status = 'APPROVED'",
                        UUID.class, AuthenticatedUser.current().userId()).stream().findFirst()
                .orElseThrow(() -> ApiException.forbidden("No approved vendor account is linked to this user"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status <> 'CANCELLED') AS orders,
                       coalesce(sum(total) FILTER (WHERE status <> 'CANCELLED'), 0) AS revenue,
                       coalesce(sum(commission) FILTER (WHERE status <> 'CANCELLED'), 0) AS commission,
                       coalesce(sum(vendor_net) FILTER (WHERE status <> 'CANCELLED'), 0) AS net,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
                FROM suborder_fact WHERE vendor_id = ? AND confirmed_at >= now() - (? * interval '1 day')
                """, vendor, days));
        out.put("salesOverTime", jdbc.queryForList("""
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day, count(s.suborder_id) AS orders,
                       coalesce(sum(s.vendor_net), 0) AS net
                FROM generate_series(date_trunc('day', now()) - ((? - 1) * interval '1 day'), date_trunc('day', now()),
                                     interval '1 day') AS d(day)
                LEFT JOIN suborder_fact s ON date_trunc('day', s.confirmed_at) = d.day AND s.vendor_id = ?
                     AND s.status <> 'CANCELLED'
                GROUP BY d.day ORDER BY d.day
                """, days, vendor));
        out.put("bestSellers", bestSellers(vendor, days));
        out.put("inventoryAlerts", jdbc.queryForList("SELECT sku, product_name, available, threshold, detected_at "
                + "FROM inventory_alert WHERE vendor_id = ? AND resolved_at IS NULL ORDER BY available", vendor));
        freshness(out);
        return out;
    }

    @GetMapping("/api/v1/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public PageView<Map<String, Object>> audit(@RequestParam(required = false) @Size(max = 80) String action,
                                               @RequestParam(required = false) UUID actorId,
                                               @RequestParam(required = false) @Size(max = 64) String resourceId,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        String where = " WHERE (?::text IS NULL OR action LIKE ? || '%') AND (?::uuid IS NULL OR actor_id = ?) "
                + "AND (?::text IS NULL OR resource_id = ?) ";
        Object[] args = {action, action, actorId, actorId, resourceId, resourceId};
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_id, service, actor_id, actor_role, action, "
                        + "resource_type, resource_id, outcome, details::text AS details, correlation_id, occurred_at "
                        + "FROM audit_log" + where + "ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                concat(args, size, (long) page * size));
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args);
        return PageView.of(rows, page, size, total == null ? 0 : total);
    }

    private List<Map<String, Object>> bestSellers(UUID vendor, int days) {
        return jdbc.queryForList("""
                SELECT product_id, max(product_name) AS product_name, sum(quantity) AS units, sum(revenue) AS revenue
                FROM item_fact WHERE NOT cancelled AND confirmed_at >= now() - (? * interval '1 day')
                  AND (?::uuid IS NULL OR vendor_id = ?)
                GROUP BY product_id ORDER BY units DESC, revenue DESC LIMIT 10
                """, days, vendor, vendor);
    }

    private Map<String, Object> operations() {
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("deadLetters24h", jdbc.queryForObject("SELECT count(*) FROM dead_letter WHERE received_at >= now() - "
                + "interval '24 hours'", Long.class));
        ops.put("stuckPendingOrders", jdbc.queryForObject("SELECT count(*) FROM order_fact WHERE status = 'PENDING' "
                + "AND created_at < now() - interval '10 minutes'", Long.class));
        ops.put("unresolvedPayments", jdbc.queryForObject("SELECT count(*) FROM payment_fact WHERE status = 'UNKNOWN'",
                Long.class));
        ops.put("failedOrders", jdbc.queryForObject("SELECT count(*) FROM order_fact WHERE status = 'FAILED'",
                Long.class));
        return ops;
    }

    private void freshness(Map<String, Object> out) {
        out.put("eventuallyConsistent", true);
        out.put("lastEventAt", jdbc.queryForObject("SELECT max(last_event_at) FROM consumer_checkpoint", Instant.class));
        out.put("generatedAt", Instant.now());
    }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
