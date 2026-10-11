package com.marketflow.order.api;

import com.marketflow.order.api.OrderDtos.OrderSummaryView;
import com.marketflow.order.api.OrderDtos.OrderView;
import com.marketflow.order.api.OrderDtos.SagaView;
import com.marketflow.order.application.OrderService;
import com.marketflow.order.domain.OrderStatus;
import com.marketflow.order.domain.saga.SagaState;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - orders & sagas")
public class AdminOrderController {

    private final OrderService orders;

    public AdminOrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/api/v1/admin/orders")
    public PageView<OrderSummaryView> list(@RequestParam(required = false) OrderStatus status,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.allOrders(status, page, size);
    }

    @GetMapping("/api/v1/admin/orders/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return orders.anyOrder(orderId);
    }

    @GetMapping("/api/v1/admin/orders/{orderId}/saga")
    @Operation(summary = "Saga state and full transition history for one order")
    public SagaView saga(@PathVariable UUID orderId) {
        return orders.saga(orderId);
    }

    @GetMapping("/api/v1/admin/sagas")
    @Operation(summary = "Sagas in a given state (e.g. RECONCILIATION_REQUIRED, COMPENSATING, FAILED)")
    public List<SagaView> byState(@RequestParam SagaState state,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return orders.sagasInState(state, limit);
    }

    @GetMapping("/api/v1/admin/sagas/summary")
    public Map<String, Long> summary() {
        return orders.sagaCounts();
    }
}
