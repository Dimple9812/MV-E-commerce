package com.marketflow.chat.persistence;

import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.domain.ConversationStatus;
import com.marketflow.chat.domain.Side;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Conversations and their participants. Plain SQL: keyset pagination and ON CONFLICT are clearer than JPA here. */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<Conversation> CONVERSATION = (rs, i) -> new Conversation(
            rs.getObject("id", UUID.class), rs.getObject("buyer_id", UUID.class), rs.getString("buyer_name"),
            rs.getObject("vendor_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getObject("suborder_id", UUID.class), ConversationStatus.valueOf(rs.getString("status")),
            instant(rs, "last_message_at"), (Long) rs.getObject("last_message_id"), instant(rs, "created_at"));

    public Optional<Conversation> findById(UUID id) {
        return jdbc.query("SELECT * FROM conversation WHERE id = ?", CONVERSATION, id).stream().findFirst();
    }

    /** Row-locks the conversation so concurrent sends update last_message_* in order. */
    public Optional<Conversation> findForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM conversation WHERE id = ? FOR UPDATE", CONVERSATION, id).stream().findFirst();
    }

    public Optional<Conversation> findByContext(UUID buyerId, UUID vendorId, UUID productId, UUID suborderId) {
        return jdbc.query("""
                SELECT * FROM conversation
                WHERE buyer_id = ? AND vendor_id = ? AND product_id IS NOT DISTINCT FROM ?
                  AND suborder_id IS NOT DISTINCT FROM ?
                """, CONVERSATION, buyerId, vendorId, productId, suborderId).stream().findFirst();
    }

    /** @return true if this call created the conversation, false if it already existed (concurrent starts too) */
    public boolean insertIfAbsent(UUID id, UUID buyerId, String buyerName, UUID vendorId, UUID productId,
                                  UUID suborderId, Instant now) {
        return jdbc.update("""
                INSERT INTO conversation (id, buyer_id, buyer_name, vendor_id, product_id, suborder_id, status,
                                          last_message_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                ON CONFLICT ON CONSTRAINT ux_conversation_context DO NOTHING
                """, id, buyerId, buyerName, vendorId, productId, suborderId, Timestamp.from(now),
                Timestamp.from(now)) == 1;
    }

    public void touch(UUID conversationId, long messageId, Instant at) {
        jdbc.update("""
                UPDATE conversation SET last_message_id = ?, last_message_at = ?
                WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)
                """, messageId, Timestamp.from(at), conversationId, messageId);
    }

    public void setStatus(UUID conversationId, ConversationStatus status) {
        jdbc.update("UPDATE conversation SET status = ? WHERE id = ?", status.name(), conversationId);
    }

    public List<Conversation> bySuborder(UUID suborderId) {
        return jdbc.query("SELECT * FROM conversation WHERE suborder_id = ? ORDER BY created_at", CONVERSATION,
                suborderId);
    }

    // ---------- participants ----------

    public record Participant(UUID conversationId, UUID userId, Side side, long lastReadMessageId, boolean muted) {
    }

    private static final RowMapper<Participant> PARTICIPANT = (rs, i) -> new Participant(
            rs.getObject("conversation_id", UUID.class), rs.getObject("user_id", UUID.class),
            Side.valueOf(rs.getString("side")), rs.getLong("last_read_message_id"), rs.getBoolean("muted"));

    public void addParticipant(UUID conversationId, UUID userId, Side side) {
        jdbc.update("""
                INSERT INTO conversation_participant (conversation_id, user_id, side) VALUES (?, ?, ?)
                ON CONFLICT (conversation_id, user_id) DO NOTHING
                """, conversationId, userId, side.name());
    }

    public List<Participant> participants(UUID conversationId) {
        return jdbc.query("SELECT * FROM conversation_participant WHERE conversation_id = ? ORDER BY side, joined_at",
                PARTICIPANT, conversationId);
    }

    public Optional<Participant> participant(UUID conversationId, UUID userId) {
        return jdbc.query("SELECT * FROM conversation_participant WHERE conversation_id = ? AND user_id = ?",
                PARTICIPANT, conversationId, userId).stream().findFirst();
    }

    /** Read markers only move forward (out-of-order frames from two tabs cannot un-read a message). */
    public long markRead(UUID conversationId, UUID userId, long messageId) {
        List<Long> v = jdbc.queryForList("""
                UPDATE conversation_participant SET last_read_message_id = GREATEST(last_read_message_id, ?)
                WHERE conversation_id = ? AND user_id = ?
                RETURNING last_read_message_id
                """, Long.class, messageId, conversationId, userId);
        return v.isEmpty() ? 0 : v.get(0);
    }

    public void setMuted(UUID conversationId, UUID userId, boolean muted) {
        jdbc.update("UPDATE conversation_participant SET muted = ? WHERE conversation_id = ? AND user_id = ?",
                muted, conversationId, userId);
    }

    /** The furthest any person on {@code side} has read: drives the "seen" ticks the other side sees. */
    public long lastReadBySide(UUID conversationId, Side side) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(last_read_message_id), 0) FROM conversation_participant
                WHERE conversation_id = ? AND side = ?
                """, Long.class, conversationId, side.name());
        return v == null ? 0 : v;
    }

    /** Conversations with unread messages for the header badge (capped per conversation for cost). */
    public int unreadTotal(UUID userId, Side side) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(u.n), 0) FROM conversation_participant p
                CROSS JOIN LATERAL (
                    SELECT count(*) AS n FROM (
                        SELECT 1 FROM message m
                        WHERE m.conversation_id = p.conversation_id AND m.id > p.last_read_message_id
                          AND m.sender_id IS DISTINCT FROM p.user_id AND m.deleted_at IS NULL
                        LIMIT 100) x) u
                WHERE p.user_id = ? AND p.side = ? AND p.muted = false
                """, Integer.class, userId, side.name());
        return v == null ? 0 : v;
    }

    // ---------- inbox ----------

    /** One inbox row: the conversation, my read state, the last message preview and my unread count. */
    public record InboxRow(Conversation conversation, long lastReadMessageId, boolean muted, int unread,
                           Long previewId, String previewType, String previewBody, String previewSenderRole,
                           boolean previewDeleted) {
    }

    private static final RowMapper<InboxRow> INBOX = (rs, i) -> new InboxRow(
            CONVERSATION.mapRow(rs, i), rs.getLong("p_last_read"), rs.getBoolean("p_muted"), rs.getInt("unread"),
            (Long) rs.getObject("m_id"), rs.getString("m_type"), rs.getString("m_body"),
            rs.getString("m_sender_role"), rs.getTimestamp("m_deleted_at") != null);

    private static final String INBOX_SELECT = """
            SELECT c.*, p.last_read_message_id AS p_last_read, p.muted AS p_muted,
                   m.id AS m_id, m.type AS m_type, m.body AS m_body, m.sender_role AS m_sender_role,
                   m.deleted_at AS m_deleted_at,
                   (SELECT count(*) FROM (
                        SELECT 1 FROM message x
                        WHERE x.conversation_id = c.id AND x.id > p.last_read_message_id
                          AND x.sender_id IS DISTINCT FROM p.user_id AND x.deleted_at IS NULL
                        LIMIT 100) u) AS unread
            FROM conversation_participant p
            JOIN conversation c ON c.id = p.conversation_id
            LEFT JOIN message m ON m.id = c.last_message_id
            """;

    /**
     * Keyset page of my conversations, newest activity first. Conversations without any message yet are left out
     * (opening the chat window creates the conversation; it shows up once someone writes).
     */
    public List<InboxRow> inbox(UUID userId, Side side, Instant cursorAt, UUID cursorId, int limit) {
        List<Object> args = new ArrayList<>(List.of(userId, side.name()));
        String keyset = "";
        if (cursorAt != null) {
            keyset = " AND (c.last_message_at, c.id) < (?, ?)";
            args.add(Timestamp.from(cursorAt));
            args.add(cursorId);
        }
        args.add(limit);
        return jdbc.query(INBOX_SELECT + """
                WHERE p.user_id = ? AND p.side = ? AND c.last_message_id IS NOT NULL
                """ + keyset + " ORDER BY c.last_message_at DESC, c.id DESC LIMIT ?", INBOX, args.toArray());
    }

    public Optional<InboxRow> inboxRow(UUID userId, UUID conversationId) {
        return jdbc.query(INBOX_SELECT + " WHERE p.user_id = ? AND c.id = ?", INBOX, userId, conversationId)
                .stream().findFirst();
    }

    /** Admin listing: newest activity first, optionally one vendor or only conversations with open reports. */
    public record AdminRow(Conversation conversation, int openReports, int violations, int messages) {
    }

    public List<AdminRow> adminList(UUID vendorId, boolean reportedOnly, Instant cursorAt, UUID cursorId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE c.last_message_id IS NOT NULL");
        if (vendorId != null) {
            where.append(" AND c.vendor_id = ?");
            args.add(vendorId);
        }
        if (reportedOnly) {
            where.append(" AND EXISTS (SELECT 1 FROM conversation_report r WHERE r.conversation_id = c.id"
                    + " AND r.status = 'OPEN')");
        }
        if (cursorAt != null) {
            where.append(" AND (c.last_message_at, c.id) < (?, ?)");
            args.add(Timestamp.from(cursorAt));
            args.add(cursorId);
        }
        args.add(limit);
        return jdbc.query("""
                SELECT c.*,
                  (SELECT count(*) FROM conversation_report r WHERE r.conversation_id = c.id AND r.status = 'OPEN') AS reports,
                  (SELECT count(*) FROM content_violation v WHERE v.conversation_id = c.id) AS violations,
                  (SELECT count(*) FROM message m WHERE m.conversation_id = c.id) AS messages
                FROM conversation c
                """ + where + " ORDER BY c.last_message_at DESC, c.id DESC LIMIT ?",
                (rs, i) -> new AdminRow(CONVERSATION.mapRow(rs, i), rs.getInt("reports"), rs.getInt("violations"),
                        rs.getInt("messages")), args.toArray());
    }

    /** People who share a recently active conversation with {@code userId}: they see that person's presence. */
    public List<UUID> recentCounterparts(UUID userId, int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT other.user_id FROM conversation_participant me
                JOIN conversation c ON c.id = me.conversation_id
                JOIN conversation_participant other ON other.conversation_id = c.id AND other.side <> me.side
                WHERE me.user_id = ? AND c.last_message_at > now() - interval '30 days'
                LIMIT ?
                """, UUID.class, userId, limit);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
