package com.marketflow.notification.api;

import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Notifications")
public class NotificationController {

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InboxItem(UUID id, String template, String subject, String body, String link, Instant createdAt,
                            Instant readAt) {
    }

    @GetMapping("/api/v1/notifications")
    public PageView<InboxItem> inbox(@RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID me = AuthenticatedUser.current().userId();
        List<InboxItem> items = jdbc.query("""
                SELECT id, template, subject, body, link, created_at, read_at FROM notification
                WHERE recipient_user_id = ? AND channel = 'IN_APP' ORDER BY created_at DESC LIMIT ? OFFSET ?
                """, (rs, i) -> new InboxItem(rs.getObject("id", UUID.class), rs.getString("template"),
                rs.getString("subject"), rs.getString("body"), rs.getString("link"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()), me, size,
                (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_user_id = ? "
                + "AND channel = 'IN_APP'", Long.class, me);
        return PageView.of(items, page, size, total == null ? 0 : total);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    public Map<String, Long> unread() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_user_id = ? "
                + "AND channel = 'IN_APP' AND read_at IS NULL", Long.class, AuthenticatedUser.current().userId());
        return Map.of("unread", n == null ? 0 : n);
    }

    @PostMapping("/api/v1/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        int n = jdbc.update("UPDATE notification SET read_at = coalesce(read_at, now()) WHERE id = ? "
                + "AND recipient_user_id = ? AND channel = 'IN_APP'", id, AuthenticatedUser.current().userId());
        if (n == 0) {
            throw ApiException.notFound("Notification");
        }
    }

    @PostMapping("/api/v1/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        jdbc.update("UPDATE notification SET read_at = now() WHERE recipient_user_id = ? AND channel = 'IN_APP' "
                + "AND read_at IS NULL", AuthenticatedUser.current().userId());
    }

    @GetMapping("/api/v1/admin/notifications/failed")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> failed(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        // recipient addresses are masked: operators need the failure, not the personal data
        return jdbc.queryForList("""
                SELECT id, template, channel, attempts, last_error, created_at,
                       regexp_replace(recipient_email, '^(.).*(@.*)$', '\\1***\\2') AS recipient
                FROM notification WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT ?
                """, limit);
    }

    @GetMapping("/api/v1/admin/notifications/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> stats() {
        return jdbc.queryForList("SELECT channel, status, count(*) AS count FROM notification GROUP BY channel, status "
                + "ORDER BY channel, status");
    }
}
