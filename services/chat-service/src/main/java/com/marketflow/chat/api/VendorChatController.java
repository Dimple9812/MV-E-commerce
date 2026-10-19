package com.marketflow.chat.api;

import com.marketflow.chat.api.ChatDtos.ConversationSummary;
import com.marketflow.chat.api.ChatDtos.CursorPage;
import com.marketflow.chat.api.ChatDtos.QuickReplyRequest;
import com.marketflow.chat.api.ChatDtos.QuickReplyView;
import com.marketflow.chat.application.ConversationService;
import com.marketflow.chat.application.QuickReplyService;
import com.marketflow.chat.domain.Side;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The shop inbox and saved quick replies. Reading and writing a conversation uses /api/v1/chat/conversations/{id}. */
@RestController
@RequestMapping("/api/v1/vendor/chat")
@PreAuthorize("hasRole('VENDOR')")
@Validated
@Tag(name = "Vendor - chat")
public class VendorChatController {

    private final ConversationService conversations;
    private final QuickReplyService quickReplies;

    public VendorChatController(ConversationService conversations, QuickReplyService quickReplies) {
        this.conversations = conversations;
        this.quickReplies = quickReplies;
    }

    @GetMapping("/conversations")
    public CursorPage<ConversationSummary> inbox(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return conversations.inbox(Actors.current(), Side.VENDOR, cursor, limit);
    }

    @GetMapping("/quick-replies")
    public List<QuickReplyView> quickReplies() {
        return quickReplies.list(Actors.current());
    }

    @PostMapping("/quick-replies")
    @ResponseStatus(HttpStatus.CREATED)
    public QuickReplyView create(@Valid @RequestBody QuickReplyRequest req) {
        return quickReplies.create(Actors.current(), req);
    }

    @PutMapping("/quick-replies/{id}")
    public QuickReplyView update(@PathVariable UUID id, @Valid @RequestBody QuickReplyRequest req) {
        return quickReplies.update(Actors.current(), id, req);
    }

    @DeleteMapping("/quick-replies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        quickReplies.delete(Actors.current(), id);
    }
}
