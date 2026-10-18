package com.marketflow.chat.persistence;

import com.marketflow.chat.domain.Side;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Attachments, reports, content violations, quick replies and response statistics. */
@Repository
public class SupportRepository {

    private final JdbcTemplate jdbc;

    public SupportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- attachments ----------

    public record AttachmentMeta(UUID id, UUID conversationId, UUID uploaderId, String contentType, int sizeBytes) {
    }

    public record AttachmentData(AttachmentMeta meta, byte[] bytes) {
    }

    public void insertAttachment(UUID id, UUID conversationId, UUID uploaderId, String contentType, byte[] bytes) {
        jdbc.update("""
                INSERT INTO attachment (id, conversation_id, uploader_id, content_type, size_bytes, bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, conversationId, uploaderId, contentType, bytes.length, bytes);
    }

    private static final RowMapper<AttachmentMeta> META = (rs, i) -> new AttachmentMeta(
            rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
            rs.getObject("uploader_id", UUID.class), rs.getString("content_type"), rs.getInt("size_bytes"));

    public Optional<AttachmentMeta> attachmentMeta(UUID id) {
        return jdbc.query("SELECT id, conversation_id, uploader_id, content_type, size_bytes FROM attachment WHERE id = ?",
                META, id).stream().findFirst();
    }

    public Optional<AttachmentData> attachment(UUID id) {
        return jdbc.query("SELECT * FROM attachment WHERE id = ?",
                (rs, i) -> new AttachmentData(META.mapRow(rs, i), rs.getBytes("bytes")), id).stream().findFirst();
    }

    // ---------- reports ----------

    public record Report(UUID id, UUID conversationId, UUID reporterId, Side reporterSide, String reason,
                         String details, String status, String resolution, String resolutionNote, UUID resolvedBy,
                         Instant resolvedAt, Instant createdAt) {
    }

    private static final RowMapper<Report> REPORT = (rs, i) -> new Report(rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class), rs.getObject("reporter_id", UUID.class),
            Side.valueOf(rs.getString("reporter_side")), rs.getString("reason"), rs.getString("details"),
            rs.getString("status"), rs.getString("resolution"), rs.getString("resolution_note"),
            rs.getObject("resolved_by", UUID.class), ConversationRepository.instant(rs, "resolved_at"),
            ConversationRepository.instant(rs, "created_at"));

    /** @return false when this person already has an open report on the conversation */
    public boolean insertReport(UUID id, UUID conversationId, UUID reporterId, Side side, String reason,
                                String details) {
        // ON CONFLICT instead of catching the exception: a failed statement would abort the whole transaction
        return jdbc.update("""
                INSERT INTO conversation_report (id, conversation_id, reporter_id, reporter_side, reason, details)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, reporter_id) WHERE status = 'OPEN' DO NOTHING
                """, id, conversationId, reporterId, side.name(), reason, details) == 1;
    }

    public Optional<Report> report(UUID id) {
        return jdbc.query("SELECT * FROM conversation_report WHERE id = ?", REPORT, id).stream().findFirst();
    }

    public Optional<Report> reportForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM conversation_report WHERE id = ? FOR UPDATE", REPORT, id).stream().findFirst();
    }

    public List<Report> reports(String status, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (status != null) {
            where = " WHERE status = ?";
            args.add(status);
        }
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT * FROM conversation_report" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                REPORT, args.toArray());
    }

    public long countReports(String status) {
        Long n = status == null
                ? jdbc.queryForObject("SELECT count(*) FROM conversation_report", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM conversation_report WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    public List<Report> reportsOf(UUID conversationId) {
        return jdbc.query("SELECT * FROM conversation_report WHERE conversation_id = ? ORDER BY created_at DESC",
                REPORT, conversationId);
    }

    public void resolveReport(UUID id, String resolution, String note, UUID adminId) {
        jdbc.update("""
                UPDATE conversation_report SET status = 'RESOLVED', resolution = ?, resolution_note = ?,
                    resolved_by = ?, resolved_at = now()
                WHERE id = ?
                """, resolution, note, adminId, id);
    }

    // ---------- content violations ----------

    public void recordViolation(UUID conversationId, UUID userId, String kinds) {
        jdbc.update("INSERT INTO content_violation (conversation_id, user_id, kinds) VALUES (?, ?, ?)",
                conversationId, userId, kinds);
    }

    public int violationCount(UUID conversationId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM content_violation WHERE conversation_id = ?",
                Integer.class, conversationId);
        return n == null ? 0 : n;
    }

    // ---------- quick replies ----------

    public record QuickReply(UUID id, UUID vendorId, String title, String body, int sortOrder) {
    }

    private static final RowMapper<QuickReply> QUICK = (rs, i) -> new QuickReply(rs.getObject("id", UUID.class),
            rs.getObject("vendor_id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getInt("sort_order"));

    public List<QuickReply> quickReplies(UUID vendorId) {
        return jdbc.query("SELECT * FROM quick_reply WHERE vendor_id = ? ORDER BY sort_order, created_at", QUICK,
                vendorId);
    }

    public int countQuickReplies(UUID vendorId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM quick_reply WHERE vendor_id = ?", Integer.class, vendorId);
        return n == null ? 0 : n;
    }

    public void insertQuickReply(UUID id, UUID vendorId, String title, String body, int sortOrder) {
        jdbc.update("INSERT INTO quick_reply (id, vendor_id, title, body, sort_order) VALUES (?, ?, ?, ?, ?)",
                id, vendorId, title, body, sortOrder);
    }

    public boolean updateQuickReply(UUID id, UUID vendorId, String title, String body, int sortOrder) {
        return jdbc.update("""
                UPDATE quick_reply SET title = ?, body = ?, sort_order = ?, updated_at = now()
                WHERE id = ? AND vendor_id = ?
                """, title, body, sortOrder, id, vendorId) == 1;
    }

    public boolean deleteQuickReply(UUID id, UUID vendorId) {
        return jdbc.update("DELETE FROM quick_reply WHERE id = ? AND vendor_id = ?", id, vendorId) == 1;
    }

    public Optional<QuickReply> quickReply(UUID id, UUID vendorId) {
        return jdbc.query("SELECT * FROM quick_reply WHERE id = ? AND vendor_id = ?", QUICK, id, vendorId).stream()
                .findFirst();
    }

    // ---------- response statistics ----------

    public Optional<Integer> medianReplySeconds(UUID vendorId) {
        return jdbc.queryForList("SELECT median_first_reply_seconds FROM vendor_response_stats WHERE vendor_id = ?",
                Integer.class, vendorId).stream().findFirst();
    }

    /**
     * Recomputes every vendor's median first-reply time over the last 30 days. A "question" is a buyer message
     * whose previous person-message in the conversation was not also the buyer's (the start of a turn); its answer
     * is the next vendor message. Vendors with fewer than three answered questions get no figure.
     *
     * <p>The transaction-scoped advisory lock makes concurrent runs on several instances cheap no-ops.
     */
    public int recomputeResponseStats() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(4711001)", Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            return 0;
        }
        return jdbc.update("""
                WITH people AS (
                    SELECT m.id, m.conversation_id, c.vendor_id, m.sender_role, m.created_at,
                           lag(m.sender_role) OVER (PARTITION BY m.conversation_id ORDER BY m.id) AS prev_role
                    FROM message m JOIN conversation c ON c.id = m.conversation_id
                    WHERE m.sender_role IN ('BUYER', 'VENDOR') AND m.created_at > now() - interval '30 days'
                ), turns AS (
                    SELECT q.vendor_id, q.created_at AS asked_at,
                           (SELECT min(a.created_at) FROM message a
                            WHERE a.conversation_id = q.conversation_id AND a.sender_role = 'VENDOR' AND a.id > q.id)
                               AS answered_at
                    FROM people q
                    WHERE q.sender_role = 'BUYER' AND q.prev_role IS DISTINCT FROM 'BUYER'
                ), stats AS (
                    SELECT vendor_id,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM answered_at - asked_at)) AS median,
                           count(*) AS n
                    FROM turns WHERE answered_at IS NOT NULL
                    GROUP BY vendor_id HAVING count(*) >= 3
                )
                INSERT INTO vendor_response_stats (vendor_id, median_first_reply_seconds, sample_size, updated_at)
                SELECT vendor_id, GREATEST(1, round(median))::int, n, now() FROM stats
                ON CONFLICT (vendor_id) DO UPDATE SET median_first_reply_seconds = EXCLUDED.median_first_reply_seconds,
                    sample_size = EXCLUDED.sample_size, updated_at = now()
                """);
    }
}
