package com.marketflow.chat.api;

import com.marketflow.chat.api.ChatDtos.AdminConversationDetail;
import com.marketflow.chat.api.ChatDtos.AdminConversationSummary;
import com.marketflow.chat.api.ChatDtos.CursorPage;
import com.marketflow.chat.api.ChatDtos.MessagePage;
import com.marketflow.chat.api.ChatDtos.ReportView;
import com.marketflow.chat.api.ChatDtos.ResolveReportRequest;
import com.marketflow.chat.application.MessageService;
import com.marketflow.chat.application.ReportService;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only access to conversations for disputes, and report handling. Administrators see deleted message
 * text (marked deleted) because a dispute may hinge on it; they cannot write in conversations.
 */
@RestController
@RequestMapping("/api/v1/admin/chat")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - chat")
public class AdminChatController {

    private final ReportService reports;
    private final MessageService messages;

    public AdminChatController(ReportService reports, MessageService messages) {
        this.reports = reports;
        this.messages = messages;
    }

    @GetMapping("/conversations")
    public CursorPage<AdminConversationSummary> conversations(@RequestParam(required = false) UUID vendorId,
                                                              @RequestParam(defaultValue = "false") boolean reported,
                                                              @RequestParam(required = false) String cursor,
                                                              @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return reports.conversations(vendorId, reported, cursor, limit);
    }

    @GetMapping("/conversations/{id}")
    public AdminConversationDetail conversation(@PathVariable UUID id) {
        return reports.detail(Actors.current(), id);
    }

    @GetMapping("/conversations/{id}/messages")
    public MessagePage messages(@PathVariable UUID id, @RequestParam(required = false) @Min(1) Long before,
                                @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return messages.page(Actors.current(), id, before, null, null, limit);
    }

    @GetMapping("/reports")
    public PageView<ReportView> reports(@RequestParam(required = false) @Pattern(regexp = "OPEN|RESOLVED") String status,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reports.reports(status, page, size);
    }

    @PostMapping("/reports/{id}/resolve")
    public ReportView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveReportRequest req) {
        return reports.resolve(Actors.current(), id, req.action(), req.note());
    }
}
