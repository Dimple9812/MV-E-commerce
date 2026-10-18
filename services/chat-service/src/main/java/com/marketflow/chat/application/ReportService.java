package com.marketflow.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.api.ChatDtos.AdminConversationDetail;
import com.marketflow.chat.api.ChatDtos.AdminConversationSummary;
import com.marketflow.chat.api.ChatDtos.CursorPage;
import com.marketflow.chat.api.ChatDtos.MessageEvent;
import com.marketflow.chat.api.ChatDtos.ReportAction;
import com.marketflow.chat.api.ChatDtos.ReportRequest;
import com.marketflow.chat.api.ChatDtos.ReportView;
import com.marketflow.chat.application.ConversationService.Cursor;
import com.marketflow.chat.application.ConversationService.Loaded;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.domain.ConversationStatus;
import com.marketflow.chat.domain.VendorRef;
import com.marketflow.chat.persistence.ConversationRepository;
import com.marketflow.chat.persistence.ConversationRepository.AdminRow;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.chat.persistence.SupportRepository;
import com.marketflow.chat.persistence.SupportRepository.Report;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reports by participants and their review by administrators (read-only access plus "close conversation"). */
@Service
public class ReportService {

    private final ConversationService conversationService;
    private final ConversationRepository conversations;
    private final ProjectionRepository projections;
    private final SupportRepository support;
    private final MessageService messages;
    private final ChatViews views;
    private final ChatFanout fanout;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final ObjectMapper json;

    public ReportService(ConversationService conversationService, ConversationRepository conversations,
                         ProjectionRepository projections, SupportRepository support, MessageService messages,
                         ChatViews views, ChatFanout fanout, OutboxWriter outbox, AuditRecorder audit,
                         ObjectMapper json) {
        this.conversationService = conversationService;
        this.conversations = conversations;
        this.projections = projections;
        this.support = support;
        this.messages = messages;
        this.views = views;
        this.fanout = fanout;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public ReportView report(ChatActor actor, UUID conversationId, ReportRequest req) {
        Loaded l = conversationService.load(actor, conversationId);
        if (!l.access().participant()) {
            throw ApiException.forbidden("Only people in the conversation can report it");
        }
        UUID id = UUID.randomUUID();
        String details = req.details() == null || req.details().isBlank() ? null : req.details().strip();
        if (!support.insertReport(id, conversationId, actor.userId(), l.access().side(), req.reason().name(), details)) {
            throw ApiException.conflict("already_reported", "You already reported this conversation; our team is reviewing it");
        }
        outbox.append(ChatEvents.TOPIC, "ConversationReported", 1, ChatEvents.AGGREGATE, conversationId.toString(),
                new ChatEvents.ConversationReported(id, conversationId, l.conversation().vendorId(),
                        l.access().side().name(), req.reason().name()));
        audit.record(actor.userId(), l.access().side() == com.marketflow.chat.domain.Side.BUYER ? "CUSTOMER" : "VENDOR",
                "chat.conversation_reported", "Conversation",
                conversationId.toString(), Map.of("reportId", id.toString(), "reason", req.reason().name()));
        return view(support.report(id).orElseThrow());
    }

    // ---------- administrators ----------

    @Transactional(readOnly = true)
    public PageView<ReportView> reports(String status, int page, int size) {
        List<ReportView> items = support.reports(status, page, size).stream().map(this::view).toList();
        return PageView.of(items, page, size, support.countReports(status));
    }

    @Transactional
    public ReportView resolve(ChatActor admin, UUID reportId, ReportAction action, String note) {
        Report r = support.reportForUpdate(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        if (!"OPEN".equals(r.status())) {
            throw ApiException.conflict("already_resolved", "This report was already resolved");
        }
        String resolution = action == ReportAction.BLOCK_CONVERSATION ? "CONVERSATION_BLOCKED" : "DISMISSED";
        support.resolveReport(reportId, resolution, note == null || note.isBlank() ? null : note.strip(), admin.userId());
        if (action == ReportAction.BLOCK_CONVERSATION) {
            Conversation c = conversations.findForUpdate(r.conversationId()).orElseThrow();
            if (c.status() != ConversationStatus.BLOCKED) {
                conversations.setStatus(c.id(), ConversationStatus.BLOCKED);
                messages.postSystem(c, "This conversation was closed by MarketFlow support after a report.",
                        json.createObjectNode().put("event", "CONVERSATION_BLOCKED"));
                fanout.toUsers(conversationService.participantIds(c.id()), MessageService.MESSAGES,
                        new MessageEvent("CONVERSATION_UPDATED", c.id(), null, ConversationStatus.BLOCKED.name()));
            }
        }
        audit.record(admin.userId(), "ADMIN", "chat.report_resolved", "Conversation", r.conversationId().toString(),
                Map.of("reportId", reportId.toString(), "resolution", resolution));
        return view(support.report(reportId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminConversationSummary> conversations(UUID vendorId, boolean reportedOnly, String cursor,
                                                              int limit) {
        Cursor c = Cursor.decode(cursor);
        List<AdminRow> rows = conversations.adminList(vendorId, reportedOnly, c == null ? null : c.at(),
                c == null ? null : c.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<AdminRow> page = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.encode(page.getLast().conversation().lastMessageAt(),
                page.getLast().conversation().id()) : null;
        return new CursorPage<>(page.stream().map(row -> {
            Conversation conv = row.conversation();
            String vendorName = projections.vendor(conv.vendorId()).map(VendorRef::businessName).orElse("Shop");
            return new AdminConversationSummary(conv.id(), conv.status().name(), conv.buyerId(), conv.buyerName(),
                    conv.vendorId(), vendorName, views.context(conv), row.messages(), row.openReports(),
                    row.violations(), conv.lastMessageAt());
        }).toList(), next);
    }

    @Transactional(readOnly = true)
    public AdminConversationDetail detail(ChatActor admin, UUID conversationId) {
        Loaded l = conversationService.load(admin, conversationId);
        return new AdminConversationDetail(
                views.conversation(l.conversation(), l.access(), l.vendor(), admin.userId()),
                support.reportsOf(conversationId).stream().map(this::view).toList(),
                support.violationCount(conversationId));
    }

    private ReportView view(Report r) {
        Conversation c = conversations.findById(r.conversationId()).orElseThrow();
        String vendorName = projections.vendor(c.vendorId()).map(VendorRef::businessName).orElse("Shop");
        return new ReportView(r.id(), r.conversationId(), r.reporterId(), r.reporterSide().name(), r.reason(),
                r.details(), r.status(), r.resolution(), r.resolutionNote(), r.resolvedBy(), r.resolvedAt(),
                r.createdAt(), vendorName, c.buyerName(), support.violationCount(c.id()));
    }
}
