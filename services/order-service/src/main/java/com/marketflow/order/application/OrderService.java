package com.marketflow.order.application;

import com.marketflow.order.api.OrderDtos.OrderSummaryView;
import com.marketflow.order.api.OrderDtos.OrderView;
import com.marketflow.order.api.OrderDtos.SagaView;
import com.marketflow.order.api.OrderDtos.VendorSuborderView;
import com.marketflow.order.domain.Cancellation;
import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderStatus;
import com.marketflow.order.domain.Suborder;
import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.SagaState;
import com.marketflow.order.persistence.CancellationRepository;
import com.marketflow.order.persistence.CatalogProjection;
import com.marketflow.order.persistence.OrderRepository;
import com.marketflow.order.persistence.SagaRepository;
import com.marketflow.order.persistence.SagaTransitionRepository;
import com.marketflow.order.persistence.StatusHistoryRepository;
import com.marketflow.order.persistence.SuborderRepository;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final SuborderRepository suborders;
    private final SagaRepository sagas;
    private final SagaTransitionRepository transitions;
    private final StatusHistoryRepository history;
    private final CancellationRepository cancellations;
    private final CatalogProjection catalog;
    private final SagaSupport s;
    private final AuditRecorder audit;

    public OrderService(OrderRepository orders, SuborderRepository suborders, SagaRepository sagas,
                        SagaTransitionRepository transitions, StatusHistoryRepository history,
                        CancellationRepository cancellations, CatalogProjection catalog, SagaSupport support,
                        AuditRecorder audit) {
        this.orders = orders;
        this.suborders = suborders;
        this.sagas = sagas;
        this.transitions = transitions;
        this.history = history;
        this.cancellations = cancellations;
        this.catalog = catalog;
        this.s = support;
        this.audit = audit;
    }

    // ---------- customer ----------

    @Transactional(readOnly = true)
    public OrderView customerOrder(UUID customerId, UUID orderId) {
        Order o = orders.findOwned(orderId, customerId).orElseThrow(() -> ApiException.notFound("Order"));
        return view(o);
    }

    @Transactional(readOnly = true)
    public PageView<OrderSummaryView> customerOrders(UUID customerId, int page, int size) {
        return PageView.of(orders.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(page, size)),
                OrderSummaryView::of);
    }

    /**
     * Cancellation after confirmation: refund the payment and return the stock. Allowed only while no suborder
     * has shipped. Completion is driven by RefundCompleted + InventoryRestocked in the orchestrator.
     */
    @Transactional
    public OrderView requestCancellation(UUID customerId, UUID orderId, String reason) {
        Order o = orders.findOwned(orderId, customerId).orElseThrow(() -> ApiException.notFound("Order"));
        Order locked = orders.findForUpdate(o.getId()).orElseThrow();
        OrderStatus prev = locked.requestCancellation(reason);
        cancellations.save(new Cancellation(o.getId(), customerId, reason));
        s.orderStatus(locked, prev, "cancellation requested: " + reason, customerId);
        s.event(locked, "RefundRequested", new OrderMessages.RefundRequestedEvent(o.getId(), customerId,
                o.getGrandTotal(), reason), null);
        s.paymentCommand(locked, "RefundRequested", "CUSTOMER_CANCELLED", null);
        s.inventoryCommand(locked, "InventoryRestockRequested", "CUSTOMER_CANCELLED", null);
        audit.record(customerId, "CUSTOMER", "order.cancellation_requested", "Order", orderId.toString(),
                Map.of("reason", reason));
        return view(locked);
    }

    // ---------- vendor ----------

    @Transactional(readOnly = true)
    public UUID vendorOf(UUID userId) {
        return catalog.approvedVendorOf(userId)
                .orElseThrow(() -> ApiException.forbidden("No approved vendor account is linked to this user"));
    }

    @Transactional(readOnly = true)
    public PageView<VendorSuborderView> vendorSuborders(UUID vendorId, Suborder.Status status, int page, int size) {
        return PageView.of(suborders.findForVendor(vendorId, status, PageRequest.of(page, size)),
                VendorSuborderView::of);
    }

    @Transactional(readOnly = true)
    public VendorSuborderView vendorSuborder(UUID vendorId, UUID suborderId) {
        return VendorSuborderView.of(suborders.findOwned(suborderId, vendorId)
                .orElseThrow(() -> ApiException.notFound("Suborder")));
    }

    @Transactional
    public VendorSuborderView ship(UUID userId, UUID vendorId, UUID suborderId, String carrier, String tracking) {
        Suborder sub = suborders.findOwned(suborderId, vendorId).orElseThrow(() -> ApiException.notFound("Suborder"));
        Order order = orders.findForUpdate(sub.getOrder().getId()).orElseThrow();
        if (order.getStatus() == OrderStatus.CANCELLATION_REQUESTED || order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("order_cancelled", "The customer cancelled this order");
        }
        String from = sub.getStatus().name();
        sub.ship(carrier, tracking);
        s.suborderStatus(order, sub.getId(), from, sub.getStatus().name(), carrier + " " + tracking, userId);
        OrderStatus prev = order.recomputeFulfilment();
        if (prev != null) {
            s.orderStatus(order, prev, "fulfilment update", userId);
        }
        s.event(order, "ShipmentCreated", new OrderMessages.ShipmentCreated(order.getId(), sub.getId(), vendorId,
                order.getCustomerId(), carrier, tracking), null);
        return VendorSuborderView.of(sub);
    }

    @Transactional
    public VendorSuborderView deliver(UUID userId, UUID vendorId, UUID suborderId) {
        Suborder sub = suborders.findOwned(suborderId, vendorId).orElseThrow(() -> ApiException.notFound("Suborder"));
        Order order = orders.findForUpdate(sub.getOrder().getId()).orElseThrow();
        String from = sub.getStatus().name();
        sub.deliver();
        s.suborderStatus(order, sub.getId(), from, sub.getStatus().name(), "delivered", userId);
        s.event(order, "SuborderDelivered", new OrderMessages.SuborderDelivered(order.getId(), sub.getId(), vendorId,
                order.getCustomerId(), sub.getItems().stream().map(i -> i.getProductId()).distinct().toList()), null);
        OrderStatus prev = order.recomputeFulfilment();
        if (prev != null) {
            s.orderStatus(order, prev, "fulfilment update", userId);
            if (order.getStatus() == OrderStatus.DELIVERED) {
                s.event(order, "OrderDelivered", new OrderMessages.OrderStatusChanged(order.getId(),
                        order.getCustomerId(), "DELIVERED", null), null);
            }
        }
        return VendorSuborderView.of(sub);
    }

    // ---------- admin ----------

    @Transactional(readOnly = true)
    public PageView<OrderSummaryView> allOrders(OrderStatus status, int page, int size) {
        var p = PageRequest.of(page, size);
        return PageView.of(status == null ? orders.findAllByOrderByCreatedAtDesc(p)
                : orders.findByStatusOrderByCreatedAtDesc(status, p), OrderSummaryView::of);
    }

    @Transactional(readOnly = true)
    public OrderView anyOrder(UUID orderId) {
        return view(orders.findDetailed(orderId).orElseThrow(() -> ApiException.notFound("Order")));
    }

    @Transactional(readOnly = true)
    public SagaView saga(UUID orderId) {
        CheckoutSaga saga = sagas.findById(orderId).orElseThrow(() -> ApiException.notFound("Saga"));
        return SagaView.of(saga, transitions.findByOrderIdOrderByIdAsc(orderId));
    }

    @Transactional(readOnly = true)
    public List<SagaView> sagasInState(SagaState state, int limit) {
        return sagas.findByStateOrderByUpdatedAtDesc(state, PageRequest.of(0, limit)).stream()
                .map(sg -> SagaView.of(sg, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> sagaCounts() {
        Map<String, Long> m = new java.util.TreeMap<>();
        sagas.countByState().forEach(c -> m.put(c.getState().name(), c.getTotal()));
        return m;
    }

    private OrderView view(Order o) {
        return OrderView.of(o, sagas.findById(o.getId()).orElse(null), history.findByOrderIdOrderByIdAsc(o.getId()));
    }
}
