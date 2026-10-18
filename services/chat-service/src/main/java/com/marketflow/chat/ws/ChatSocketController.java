package com.marketflow.chat.ws;

import com.marketflow.chat.api.ChatDtos.ErrorEvent;
import com.marketflow.chat.api.ChatDtos.ReadFrame;
import com.marketflow.chat.api.ChatDtos.SendFrame;
import com.marketflow.chat.api.ChatDtos.TypingFrame;
import com.marketflow.chat.application.ConversationService;
import com.marketflow.chat.application.MessageService;
import com.marketflow.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP entry points. They only translate frames: the services enforce exactly the same rules as the REST API.
 * The sender's identity is the session principal (set from the JWT on CONNECT), never a field of the frame.
 *
 * <ul>
 *   <li>{@code /app/chat.send}: the stored message arrives on {@code /user/queue/messages} for every participant,
 *       the sender's own sessions included (that delivery is the acknowledgement; match it by clientMessageId)</li>
 *   <li>{@code /app/chat.typing}: delivered to the other side on {@code /user/queue/typing}</li>
 *   <li>{@code /app/chat.read}: receipts on {@code /user/queue/receipts}</li>
 *   <li>failures: {@code /user/queue/errors}, to the sending session only</li>
 * </ul>
 */
@Controller
public class ChatSocketController {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketController.class);

    private final MessageService messages;
    private final ConversationService conversations;

    public ChatSocketController(MessageService messages, ConversationService conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    /** Carries the frame's ids to the error handler so the client can match the failure to its pending message. */
    static final class FrameFailure extends RuntimeException {
        final ApiException failure;
        final UUID conversationId;
        final UUID clientMessageId;

        FrameFailure(ApiException cause, UUID conversationId, UUID clientMessageId) {
            super(cause.getMessage(), cause, false, false);
            this.failure = cause;
            this.conversationId = conversationId;
            this.clientMessageId = clientMessageId;
        }
    }

    private static ChatPrincipal principal(Principal p) {
        if (p instanceof ChatPrincipal cp) {
            return cp;
        }
        throw ApiException.unauthorized("Authentication required");
    }

    private static UUID required(UUID conversationId) {
        if (conversationId == null) {
            throw ApiException.badRequest("validation_failed", "conversationId is required");
        }
        return conversationId;
    }

    @MessageMapping("chat.send")
    public void send(@Payload SendFrame frame, Principal principal) {
        try {
            messages.send(principal(principal).actor(), required(frame.conversationId()), frame.toRequest());
        } catch (ApiException e) {
            throw new FrameFailure(e, frame.conversationId(), frame.clientMessageId());
        }
    }

    @MessageMapping("chat.typing")
    public void typing(@Payload TypingFrame frame, Principal principal) {
        try {
            messages.typing(principal(principal).actor(), required(frame.conversationId()), frame.typing());
        } catch (ApiException e) {
            throw new FrameFailure(e, frame.conversationId(), null);
        }
    }

    @MessageMapping("chat.read")
    public void read(@Payload ReadFrame frame, Principal principal) {
        try {
            conversations.markRead(principal(principal).actor(), required(frame.conversationId()),
                    frame.lastReadMessageId());
        } catch (ApiException e) {
            throw new FrameFailure(e, frame.conversationId(), null);
        }
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorEvent onFailure(Exception e) {
        if (e instanceof FrameFailure f) {
            return new ErrorEvent(f.failure.code(), f.failure.getMessage(), f.conversationId, f.clientMessageId);
        }
        if (e instanceof ApiException a) {
            return new ErrorEvent(a.code(), a.getMessage(), null, null);
        }
        if (e instanceof org.springframework.messaging.converter.MessageConversionException) {
            return new ErrorEvent("bad_request", "Malformed frame", null, null);
        }
        log.error("Unhandled STOMP frame failure", e);
        return new ErrorEvent("internal_error", "Something went wrong. Try again.", null, null);
    }
}
