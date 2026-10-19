package com.marketflow.chat.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.domain.Message;
import com.marketflow.chat.domain.MessageType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MessageRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Message> mapper;

    public MessageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Message(rs.getLong("id"), rs.getObject("conversation_id", UUID.class),
                rs.getObject("sender_id", UUID.class), rs.getString("sender_role"),
                MessageType.valueOf(rs.getString("type")), rs.getString("body"), rs.getString("attachment_url"),
                readJson(rs.getString("metadata")), rs.getObject("client_message_id", UUID.class),
                ConversationRepository.instant(rs, "created_at"), ConversationRepository.instant(rs, "edited_at"),
                ConversationRepository.instant(rs, "deleted_at"));
    }

    public record NewMessage(UUID conversationId, UUID senderId, String senderRole, MessageType type, String body,
                             String attachmentUrl, JsonNode metadata, UUID clientMessageId, Instant createdAt) {
    }

    /**
     * Inserts the message unless the sender already sent one with this client id.
     *
     * @return the new message, or empty when the client id was already used (the caller then loads that one)
     */
    public Optional<Message> insert(NewMessage m) {
        return jdbc.query("""
                INSERT INTO message (conversation_id, sender_id, sender_role, type, body, attachment_url, metadata,
                                     client_message_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT ON CONSTRAINT ux_message_client_id DO NOTHING
                RETURNING *
                """, mapper, m.conversationId(), m.senderId(), m.senderRole(), m.type().name(), m.body(),
                m.attachmentUrl(), writeJson(m.metadata()), m.clientMessageId(), Timestamp.from(m.createdAt()))
                .stream().findFirst();
    }

    public Optional<Message> findByClientId(UUID senderId, UUID clientMessageId) {
        return jdbc.query("SELECT * FROM message WHERE sender_id = ? AND client_message_id = ?", mapper, senderId,
                clientMessageId).stream().findFirst();
    }

    public Optional<Message> findById(long id) {
        return jdbc.query("SELECT * FROM message WHERE id = ?", mapper, id).stream().findFirst();
    }

    /** Older messages, newest first from the database, returned oldest first. */
    public List<Message> before(UUID conversationId, Long beforeId, int limit) {
        List<Message> rows = jdbc.query("""
                SELECT * FROM message WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?
                """, mapper, conversationId, beforeId == null ? Long.MAX_VALUE : beforeId, limit);
        return rows.reversed();
    }

    /** Newer messages (missed-message resync after a reconnect), oldest first. */
    public List<Message> after(UUID conversationId, long afterId, int limit) {
        return jdbc.query("""
                SELECT * FROM message WHERE conversation_id = ? AND id > ? ORDER BY id ASC LIMIT ?
                """, mapper, conversationId, afterId, limit);
    }

    /** Messages edited or deleted after {@code since}: lets a reconnecting client refresh what it already shows. */
    public List<Message> changedSince(UUID conversationId, Instant since, int limit) {
        return jdbc.query("""
                SELECT * FROM message WHERE conversation_id = ?
                  AND (edited_at > ? OR deleted_at > ?) ORDER BY id ASC LIMIT ?
                """, mapper, conversationId, Timestamp.from(since), Timestamp.from(since), limit);
    }

    public Optional<Message> edit(long id, String body, Instant at) {
        return jdbc.query("""
                UPDATE message SET body = ?, edited_at = ? WHERE id = ? AND deleted_at IS NULL RETURNING *
                """, mapper, body, Timestamp.from(at), id).stream().findFirst();
    }

    public Optional<Message> softDelete(long id, Instant at) {
        return jdbc.query("""
                UPDATE message SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL RETURNING *
                """, mapper, Timestamp.from(at), id).stream().findFirst();
    }

    private JsonNode readJson(String s) {
        if (s == null) {
            return null;
        }
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt message metadata", e);
        }
    }

    private String writeJson(JsonNode n) {
        if (n == null) {
            return null;
        }
        try {
            return json.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
