package com.marketflow.chat.api;

import com.marketflow.chat.api.ChatDtos.AttachmentView;
import com.marketflow.chat.api.ChatDtos.ConversationSummary;
import com.marketflow.chat.api.ChatDtos.ConversationView;
import com.marketflow.chat.api.ChatDtos.CursorPage;
import com.marketflow.chat.api.ChatDtos.EditMessageRequest;
import com.marketflow.chat.api.ChatDtos.MessagePage;
import com.marketflow.chat.api.ChatDtos.MessageView;
import com.marketflow.chat.api.ChatDtos.MuteRequest;
import com.marketflow.chat.api.ChatDtos.ReadRequest;
import com.marketflow.chat.api.ChatDtos.ReportRequest;
import com.marketflow.chat.api.ChatDtos.ReportView;
import com.marketflow.chat.api.ChatDtos.SendMessageRequest;
import com.marketflow.chat.api.ChatDtos.StartConversationRequest;
import com.marketflow.chat.api.ChatDtos.StartResult;
import com.marketflow.chat.api.ChatDtos.UnreadCounts;
import com.marketflow.chat.application.AttachmentService;
import com.marketflow.chat.application.ConversationService;
import com.marketflow.chat.application.MessageService;
import com.marketflow.chat.application.ReportService;
import com.marketflow.chat.domain.Side;
import com.marketflow.chat.persistence.SupportRepository.AttachmentData;
import com.marketflow.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Conversations of the signed-in user. The buyer inbox lives here; the shop inbox is under /api/v1/vendor/chat.
 * Endpoints that address one conversation or message work for both sides: the caller's side is derived from
 * the conversation (a person is never on both sides of the same conversation).
 */
@RestController
@RequestMapping("/api/v1/chat")
@Validated
@Tag(name = "Chat")
public class ChatController {

    private final ConversationService conversations;
    private final MessageService messages;
    private final AttachmentService attachments;
    private final ReportService reports;

    public ChatController(ConversationService conversations, MessageService messages, AttachmentService attachments,
                          ReportService reports) {
        this.conversations = conversations;
        this.messages = messages;
        this.attachments = attachments;
        this.reports = reports;
    }

    @PostMapping("/conversations")
    @Operation(summary = "Start a conversation with a shop, or get the existing one for the same product or order")
    public ResponseEntity<ConversationView> start(@Valid @RequestBody StartConversationRequest req) {
        StartResult r = conversations.start(Actors.current(), req);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.conversation());
    }

    @GetMapping("/conversations")
    @Operation(summary = "Buyer inbox, newest activity first (keyset pagination)")
    public CursorPage<ConversationSummary> inbox(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return conversations.inbox(Actors.current(), Side.BUYER, cursor, limit);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread messages as a buyer and, for sellers, in the shop inbox")
    public UnreadCounts unread() {
        return conversations.unread(Actors.current());
    }

    @GetMapping("/conversations/{id}")
    public ConversationView get(@PathVariable UUID id) {
        return conversations.get(Actors.current(), id);
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "Timeline: before=<id> pages older messages; after=<id> returns newer ones (resync), "
            + "with since=<instant> also messages edited or deleted since then")
    public MessagePage messages(@PathVariable UUID id, @RequestParam(required = false) @Min(1) Long before,
                                @RequestParam(required = false) @Min(0) Long after,
                                @RequestParam(required = false) Instant since,
                                @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        if (before != null && after != null) {
            throw ApiException.badRequest("invalid_cursor", "Use either before or after, not both");
        }
        return messages.page(Actors.current(), id, before, after, since, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    @Operation(summary = "Send a message over HTTP (same rules as /app/chat.send; idempotent on clientMessageId)")
    public ResponseEntity<MessageView> send(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest req) {
        MessageService.Sent s = messages.send(Actors.current(), id, req);
        return ResponseEntity.status(s.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(s.message());
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, Long> read(@PathVariable UUID id, @Valid @RequestBody(required = false) ReadRequest req) {
        long v = conversations.markRead(Actors.current(), id, req == null ? null : req.lastReadMessageId());
        return Map.of("lastReadMessageId", v);
    }

    @PutMapping("/conversations/{id}/mute")
    public ConversationView mute(@PathVariable UUID id, @RequestBody MuteRequest req) {
        return conversations.mute(Actors.current(), id, req.muted());
    }

    @PostMapping("/conversations/{id}/report")
    @Operation(summary = "Report the conversation to MarketFlow support")
    public ResponseEntity<ReportView> report(@PathVariable UUID id, @Valid @RequestBody ReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.report(Actors.current(), id, req));
    }

    @PatchMapping("/messages/{messageId}")
    @Operation(summary = "Edit your own text message within 10 minutes of sending it")
    public MessageView edit(@PathVariable long messageId, @Valid @RequestBody EditMessageRequest req) {
        return messages.edit(Actors.current(), messageId, req.body());
    }

    @DeleteMapping("/messages/{messageId}")
    @Operation(summary = "Delete your own message within 10 minutes of sending it (soft delete)")
    public MessageView delete(@PathVariable long messageId) {
        return messages.delete(Actors.current(), messageId);
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an image (PNG, JPEG or WebP, up to 5 MB) to send in a conversation")
    public ResponseEntity<AttachmentView> upload(@RequestParam UUID conversationId,
                                                 @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachments.upload(Actors.current(), conversationId, file.getBytes()));
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID attachmentId) {
        AttachmentData a = attachments.read(Actors.current(), attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.meta().contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(a.bytes());
    }
}
