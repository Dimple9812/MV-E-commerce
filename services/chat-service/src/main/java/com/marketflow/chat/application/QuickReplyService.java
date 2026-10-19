package com.marketflow.chat.application;

import com.marketflow.chat.api.ChatDtos.QuickReplyRequest;
import com.marketflow.chat.api.ChatDtos.QuickReplyView;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.chat.domain.VendorRef;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.chat.persistence.SupportRepository;
import com.marketflow.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Saved reply templates of a shop. They are inserted into the composer, so they go through the normal checks. */
@Service
public class QuickReplyService {

    static final int MAX_PER_VENDOR = 50;

    private final ProjectionRepository projections;
    private final SupportRepository support;

    public QuickReplyService(ProjectionRepository projections, SupportRepository support) {
        this.projections = projections;
        this.support = support;
    }

    private UUID myVendor(ChatActor actor) {
        return projections.vendorOwnedBy(actor.userId()).map(VendorRef::vendorId)
                .orElseThrow(() -> ApiException.forbidden("Only sellers have quick replies"));
    }

    @Transactional(readOnly = true)
    public List<QuickReplyView> list(ChatActor actor) {
        return support.quickReplies(myVendor(actor)).stream().map(QuickReplyService::view).toList();
    }

    @Transactional
    public QuickReplyView create(ChatActor actor, QuickReplyRequest req) {
        UUID vendor = myVendor(actor);
        if (support.countQuickReplies(vendor) >= MAX_PER_VENDOR) {
            throw ApiException.unprocessable("too_many_quick_replies", "A shop can keep up to " + MAX_PER_VENDOR
                    + " quick replies");
        }
        UUID id = UUID.randomUUID();
        support.insertQuickReply(id, vendor, req.title().strip(), req.body().strip(),
                req.sortOrder() == null ? 0 : req.sortOrder());
        return view(support.quickReply(id, vendor).orElseThrow());
    }

    @Transactional
    public QuickReplyView update(ChatActor actor, UUID id, QuickReplyRequest req) {
        UUID vendor = myVendor(actor);
        if (!support.updateQuickReply(id, vendor, req.title().strip(), req.body().strip(),
                req.sortOrder() == null ? 0 : req.sortOrder())) {
            throw ApiException.notFound("Quick reply");
        }
        return view(support.quickReply(id, vendor).orElseThrow());
    }

    @Transactional
    public void delete(ChatActor actor, UUID id) {
        if (!support.deleteQuickReply(id, myVendor(actor))) {
            throw ApiException.notFound("Quick reply");
        }
    }

    private static QuickReplyView view(SupportRepository.QuickReply q) {
        return new QuickReplyView(q.id(), q.title(), q.body(), q.sortOrder());
    }
}
