package com.marketflow.order.api;

import com.marketflow.order.api.OrderDtos.CancelRequest;
import com.marketflow.order.api.OrderDtos.CheckoutRequest;
import com.marketflow.order.api.OrderDtos.OrderSummaryView;
import com.marketflow.order.api.OrderDtos.OrderView;
import com.marketflow.order.application.CheckoutService;
import com.marketflow.order.application.OrderService;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
@Tag(name = "Orders")
public class OrderController {

    private final CheckoutService checkout;
    private final OrderService orders;

    public OrderController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping("/checkout")
    @Operation(summary = "Start a checkout (asynchronous saga)",
            description = "Returns 202 with the pending order. Poll GET /api/v1/orders/{id} until status is CONFIRMED "
                    + "or CANCELLED. Repeating the call with the same Idempotency-Key returns the same order.")
    public ResponseEntity<OrderView> checkout(
            @Parameter(required = true) @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CheckoutRequest req) {
        AuthenticatedUser user = AuthenticatedUser.current();
        CheckoutService.Result result = checkout.checkout(user, key, req);
        OrderView view = orders.customerOrder(user.userId(), result.orderId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/orders/" + result.orderId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(view);
    }

    @GetMapping
    public PageView<OrderSummaryView> mine(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.customerOrders(AuthenticatedUser.current().userId(), page, size);
    }

    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return orders.customerOrder(AuthenticatedUser.current().userId(), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a confirmed, not yet shipped order (refund + restock happen asynchronously)")
    public ResponseEntity<OrderView> cancel(@PathVariable UUID orderId, @Valid @RequestBody CancelRequest req) {
        return ResponseEntity.accepted().body(orders.requestCancellation(AuthenticatedUser.current().userId(),
                orderId, req.reason()));
    }
}
