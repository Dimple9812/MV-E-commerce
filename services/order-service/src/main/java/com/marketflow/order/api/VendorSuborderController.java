package com.marketflow.order.api;

import com.marketflow.order.api.OrderDtos.ShipRequest;
import com.marketflow.order.api.OrderDtos.VendorSuborderView;
import com.marketflow.order.application.OrderService;
import com.marketflow.order.domain.Suborder;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

@RestController
@RequestMapping("/api/v1/vendor/suborders")
@PreAuthorize("hasRole('VENDOR')")
@Validated
@Tag(name = "Vendor - suborders")
public class VendorSuborderController {

    private final OrderService orders;

    public VendorSuborderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public PageView<VendorSuborderView> list(@RequestParam(required = false) Suborder.Status status,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.vendorSuborders(orders.vendorOf(AuthenticatedUser.current().userId()), status, page, size);
    }

    @GetMapping("/{suborderId}")
    public VendorSuborderView get(@PathVariable UUID suborderId) {
        return orders.vendorSuborder(orders.vendorOf(AuthenticatedUser.current().userId()), suborderId);
    }

    @PostMapping("/{suborderId}/ship")
    public VendorSuborderView ship(@PathVariable UUID suborderId, @Valid @RequestBody ShipRequest req) {
        UUID user = AuthenticatedUser.current().userId();
        return orders.ship(user, orders.vendorOf(user), suborderId, req.carrier(), req.trackingNumber());
    }

    @PostMapping("/{suborderId}/deliver")
    public VendorSuborderView deliver(@PathVariable UUID suborderId) {
        UUID user = AuthenticatedUser.current().userId();
        return orders.deliver(user, orders.vendorOf(user), suborderId);
    }
}
