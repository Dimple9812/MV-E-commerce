package com.marketflow.chat.application;

import com.marketflow.chat.api.ChatDtos.AttachmentView;
import com.marketflow.chat.application.ConversationService.Loaded;
import com.marketflow.chat.config.ChatProperties;
import com.marketflow.chat.domain.ChatAccess;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.chat.persistence.SupportRepository;
import com.marketflow.chat.persistence.SupportRepository.AttachmentData;
import com.marketflow.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Image uploads for chat. The type is decided by magic bytes (PNG, JPEG, WebP), never by the file name or the
 * client's Content-Type; the size is capped; ids are random. Bytes live in chat_db so every instance can serve
 * them (catalog's local-volume storage would pin chat to one instance). Reading requires access to the
 * conversation, so a leaked URL is useless to anyone else.
 */
@Service
public class AttachmentService {

    private final ConversationService conversations;
    private final SupportRepository support;
    private final int maxBytes;

    public AttachmentService(ConversationService conversations, SupportRepository support, ChatProperties props) {
        this.conversations = conversations;
        this.support = support;
        this.maxBytes = props.attachmentMaxBytes();
    }

    public static String url(UUID id) {
        return "/api/v1/chat/attachments/" + id;
    }

    @Transactional
    public AttachmentView upload(ChatActor actor, UUID conversationId, byte[] bytes) {
        Loaded l = conversations.load(actor, conversationId);
        ChatAccess.write(actor, l.conversation(), l.vendor());
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("invalid_image", "Choose an image to upload");
        }
        if (bytes.length > maxBytes) {
            throw ApiException.badRequest("image_too_large", "Images can be at most " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String type = detect(bytes);
        if (type == null) {
            throw ApiException.badRequest("unsupported_image", "Only PNG, JPEG and WebP images can be sent");
        }
        UUID id = UUID.randomUUID();
        support.insertAttachment(id, conversationId, actor.userId(), type, bytes);
        return new AttachmentView(id, url(id), type, bytes.length);
    }

    @Transactional(readOnly = true)
    public AttachmentData read(ChatActor actor, UUID attachmentId) {
        SupportRepository.AttachmentMeta meta = support.attachmentMeta(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment"));
        conversations.load(actor, meta.conversationId()); // 404 unless the reader can see the conversation
        return support.attachment(attachmentId).orElseThrow(() -> ApiException.notFound("Attachment"));
    }

    static String detect(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
